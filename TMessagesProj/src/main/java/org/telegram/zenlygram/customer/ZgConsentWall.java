/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.IntroActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LoginActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.List;

/**
 * Presents the consent wall over the signed-in account (iOS SGConsentWallPresenter, docs/customer-data.md §4.1):
 * at launch / every resume, right after a login, on an account switch and whenever the cloud client reports a consent
 * change (a grant or a withdrawal here, a withdrawal on another device, a link the server ended, a deletion). It is
 * decided locally ({@link ZgCustomer#wallDecision}, the client's stored state) and never waits on the network.
 *
 * <p>While the wall blocks the selected account, LaunchActivity asks {@link #allows} before presenting a fragment:
 * everything but the wall's own pages, the login screens and the root chat list is dropped (iOS drops deep links,
 * notification taps and chat opens while {@code isWallPresented}). Telegram calls are an activity / window of their
 * own, so they stay above the wall. The app-update block (ZgAppUpdate) stays above the wall too.
 *
 * <p>The deletion screen of Settings {@link #hold holds} the wall from the confirmation until its done text was read
 * (iOS holdWall), so the wall does not cover "Zenlygram account deleted" the moment the erasure lands.
 */
public final class ZgConsentWall {

    private static boolean observing;
    /** Optional mode: the prompt appears at most once per launch (iOS: once per attach). */
    private static boolean promptShown;
    /** The slot whose wall is held, or -1. */
    private static int heldAccount = -1;

    private ZgConsentWall() {
    }

    private static final NotificationCenter.NotificationCenterDelegate observer = (id, account, args) -> {
        if (id == ZgCustomer.consentChanged) {
            schedule();
        }
    };

    /** Re-evaluates on a later main-thread turn (after the current navigation settles). */
    public static void schedule() {
        AndroidUtilities.runOnUIThread(ZgConsentWall::check, 50);
    }

    /** The deletion screen of {@code account} shows its progress or its done text: no wall over it until {@link #release}. */
    static void hold(int account) {
        heldAccount = account;
    }

    /** Ends {@link #hold} of {@code account} and decides again. */
    static void release(int account) {
        if (heldAccount == account) {
            heldAccount = -1;
            schedule();
        }
    }

    /** UI thread. Shows the wall for the selected account when its decision asks for it and it is not up yet. */
    public static void check() {
        try {
            if (!observing) {
                observing = true;
                NotificationCenter.getGlobalInstance().addObserver(observer, ZgCustomer.consentChanged);
            }
            ZgCustomer.start();
            final LaunchActivity activity = LaunchActivity.instance;
            if (activity == null || activity.isFinishing() || org.telegram.zenlygram.appupdate.ZgAppUpdate.isBlocking()) {
                return;
            }
            final int account = UserConfig.selectedAccount;
            if (!UserConfig.getInstance(account).isClientActivated() || heldAccount == account) {
                return;
            }
            final ZgConsentWallPolicy.Decision decision = ZgCustomer.wallDecision(account);
            final boolean prompt = decision.presentation == ZgConsentWallPolicy.Presentation.PROMPT;
            if (!decision.blocks() && !(prompt && !promptShown)) {
                return;
            }
            final INavigationLayout layout = activity.getActionBarLayout();
            if (layout == null || wallIn(layout.getFragmentStack())) {
                return;
            }
            final BaseFragment last = layout.getLastFragment();
            if (last == null || last instanceof LoginActivity || last instanceof IntroActivity || last.getParentActivity() == null) {
                return;
            }
            if (prompt) {
                promptShown = true;
            }
            layout.presentFragment(new INavigationLayout.NavigationParams(new ZgConsentWallActivity(true))
                .setNoAnimation(true).setCheckPresentFromDelegate(false));
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static boolean wallIn(List<BaseFragment> stack) {
        if (stack == null) {
            return false;
        }
        for (BaseFragment fragment : stack) {
            if (fragment instanceof ZgConsentWallActivity && ((ZgConsentWallActivity) fragment).presentedByWall()) {
                return true;
            }
        }
        return false;
    }

    /**
     * LaunchActivity.needPresentFragment: false drops {@code fragment} because the wall blocks the selected account
     * (then the wall is shown, if it is not up yet).
     */
    public static boolean allows(BaseFragment fragment) {
        if (fragment == null || fragment instanceof ZgConsentWallActivity || fragment instanceof ZgConsentTextActivity
            || fragment instanceof ZgConsentDeclinedActivity || fragment instanceof ZgMyDataActivity
            || fragment instanceof ZgAccountDeleteActivity || fragment instanceof LoginActivity
            || fragment instanceof IntroActivity) {
            return true;
        }
        if (fragment instanceof MainTabsActivity || (fragment instanceof DialogsActivity && ((DialogsActivity) fragment).isMainDialogList())) {
            schedule(); // the account's root: the wall goes up right after it
            return true;
        }
        try {
            final int account = UserConfig.selectedAccount;
            if (!UserConfig.getInstance(account).isClientActivated() || heldAccount == account || !ZgCustomer.wallDecision(account).blocks()) {
                return true;
            }
        } catch (Throwable t) {
            FileLog.e(t);
            return true;
        }
        schedule();
        return false;
    }
}
