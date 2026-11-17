/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pro;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

/**
 * The one UI gate of Zenlygram Pro (iOS sgEntitlementGate). It is the only place that turns a "no" from {@link ZgPro}
 * into something the user sees, so every feature answers it the same way.
 *
 * <pre>
 * // Right before a create, on the UI thread (close an ItemOptions / popup first):
 * if (!ZgProGate.check(fragment, ZgPro.GatedAction.CREATE_TASK)) {
 *     return; // keep the typed text; the gate already showed what is needed
 * }
 * </pre>
 *
 * While Pro is free {@link #check} returns true at once and shows nothing. Never call it on a read, open, unlock,
 * unhide, edit or delete path: those are never gated ({@link ZgPro.UnrestrictedAction}).
 *
 * <p>The first create that is blocked after the owner turned Pro into a subscription says so once ("Zenlygram Pro is
 * now a subscription…", iOS takePaidModeNotice), on top of the Pro screen: only for an account that never had a grant
 * (an ended or unverifiable grant has its own explanation there), and never again until another free to paid change.
 * The screen takes the notice when it is really shown ({@link ZgProActivity#forBlockedCreate}), so it is never spent
 * on a screen that could not be presented.
 */
public final class ZgProGate {

    private ZgProGate() {
    }

    /**
     * Answers the gate for {@code action} on the account of {@code fragment} (the selected account without one).
     *
     * @return true: go ahead with the create. False: stop and keep the user's input. When Pro is not active the
     * Zenlygram Pro screen is opened (never an error or a retry); for an unavailable capability (the AI assistant)
     * nothing is shown.
     */
    @UiThread
    public static boolean check(@Nullable BaseFragment fragment, ZgPro.GatedAction action) {
        final int account = fragment != null ? fragment.getCurrentAccount() : UserConfig.selectedAccount;
        switch (ZgPro.gate(account, action)) {
            case ALLOWED:
                return true;
            case UNAVAILABLE:
                return false;
            case BLOCKED:
            default:
                presentProScreen(fragment, ZgProActivity.forBlockedCreate(account));
                return false;
        }
    }

    /** Opens the Zenlygram Pro screen from {@code fragment}, or from the top screen when that cannot present. */
    @UiThread
    public static void presentProScreen(@Nullable BaseFragment fragment) {
        presentProScreen(fragment, new ZgProActivity());
    }

    private static void presentProScreen(@Nullable BaseFragment fragment, BaseFragment screen) {
        if (fragment != null && fragment.presentFragment(screen)) {
            return;
        }
        final BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last != null && last != fragment) {
            last.presentFragment(screen);
        }
    }

}
