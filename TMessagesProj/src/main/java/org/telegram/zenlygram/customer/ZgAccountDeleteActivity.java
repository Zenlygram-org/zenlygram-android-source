/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.telegram.messenger.LocaleController.getString;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.DeleteFlow;
import org.telegram.messenger.zenlygram.customer.ZenlyConsent;
import org.telegram.messenger.zenlygram.customer.ZenlyCustomer;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * "Delete my Zenlygram account and data" (iOS SGAccountDeleteView; Google Play's in-app account deletion path),
 * reached from Data &amp; consent, the settings hub and the wall's decline and rights pages — deletion never needs a
 * consent. What is deleted, what is kept, that the Telegram account stays and the timing; then the red action, which
 * asks once more ("This can't be undone") before anything is sent.
 *
 * <p>The deletion is the cloud client's ({@code ZenlyCustomer.deleteAccount}): it writes its record before the first
 * send, sends {@code POST /v1/account/delete} with one Idempotency-Key for every attempt and reports each state here.
 * While it is out a progress shows; an error shows its text with Try again (the same key) and the web page; an account
 * this device has not linked is sent to the web page and nothing is requested. When the server has erased the account
 * the screen says so ("Zenlygram account deleted"); closing it acknowledges the deletion, the consent record goes with
 * it, and in a required build the wall asks again. Deleting the account never cancels a Google Play subscription:
 * the Google Play build says so, with the way to Play's subscription page, while Pro is a subscription or the account
 * holds a paid period (iOS: the billing paragraph of the Pro surfaces). Reported as {@code zg.account_delete}.
 */
public class ZgAccountDeleteActivity extends UniversalFragment implements ZenlyStats.NamedScreen, ZenlyCustomer.DeleteListener {

    private static final int ID_TEXT = 1;
    private static final int ID_OPEN_WEB = 2;
    private static final int ID_STATE = 3;
    private static final int ID_CONFIRM = 4;
    private static final int ID_RETRY = 5;
    private static final int ID_DONE = 6;
    private static final int ID_CLOSE = 7;
    private static final int ID_MANAGE_SUBSCRIPTION = 8;

    /** Google Play's subscription page (the same one the Pro screen opens). */
    private static final String PLAY_SUBSCRIPTIONS_URL = "https://play.google.com/store/account/subscriptions";

    private enum Phase {
        /** Nothing asked yet. */
        IDLE,
        /** The request is out (or its automatic retry is scheduled). */
        WORKING,
        /** A deletion of an earlier visit still waits: the user may send it again. */
        PENDING,
        /** This run could not finish now: its text, Try again (the same key) and the web page. */
        ERROR,
        /** Nothing was deleted: the user may start over, or use the web page. */
        FAILED,
        NOT_LINKED,
        /** Erased: the done text until the screen is closed. */
        DONE
    }

    private Phase phase = Phase.IDLE;
    private int errorText = R.string.ZenlyDeleteErrorUnavailable;
    /** After "too many attempts" the web page is not offered (it would be refused the same way). */
    private boolean rateLimited;
    private boolean destroyed;
    private boolean acknowledged;

    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return CustomerEvents.Screen.ZG_ACCOUNT_DELETE;
    }


    // The same look as Settings > Zenlygram and the link screen: inset cards on the glass header (iOS inset grouped).
    @Override
    protected boolean useSimpleActionBar() {
        return true;
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
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        // A link that appears or ends while the screen is open changes what it can offer.
        NotificationCenter.getInstance(currentAccount).listen(fragmentView, NotificationCenter.zenlygramProStatusChanged, args -> refresh());
        refresh();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (phase == Phase.DONE) {
            acknowledge(); // however the screen went away, its done text was shown
        }
        ZgConsentWall.release(currentAccount);
        super.onFragmentDestroy();
    }

    /** Reads the link and the stored deletion record again (each time the screen shows). */
    private void refresh() {
        if (destroyed) {
            return;
        }
        final boolean linked = ZgCustomer.isLinked(currentAccount);
        final DeleteFlow.Record record = ZgCustomer.deletion(currentAccount);
        if (record != null && record.state == DeleteFlow.State.DONE) {
            phase = Phase.DONE;
            ZgConsentWall.hold(currentAccount); // the done text is read first
        } else if (phase != Phase.WORKING && phase != Phase.DONE) {
            if (record != null && record.state == DeleteFlow.State.PENDING) {
                if (!linked) {
                    // The link went away under a waiting deletion: the client settles the record (nothing is sent) and
                    // answers not linked or failed through onDeleteState.
                    ZenlyCustomer.deleteAccount(currentAccount, this);
                    update();
                    return;
                }
                // A run waiting for the user's retry keeps its error text; one of an earlier visit says it is pending.
                phase = phase == Phase.ERROR ? Phase.ERROR : Phase.PENDING;
            } else if (!linked) {
                phase = Phase.NOT_LINKED;
            } else if (record != null && record.state == DeleteFlow.State.FAILED) {
                phase = Phase.FAILED;
            } else {
                phase = Phase.IDLE;
            }
        }
        update();
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    /** Every state of a run, on the main thread. */
    @Override
    public void onDeleteState(int account, DeleteFlow.Outcome outcome) {
        if (account != currentAccount) {
            return;
        }
        if (destroyed) {
            if (outcome == DeleteFlow.Outcome.DONE) {
                // Nobody read the done text here. Required: the wall shows it as its notice and acknowledges.
                // Optional: there is no wall to show it, so the record goes at once.
                if (ZgCustomer.isRequired()) {
                    // A wall that is already up reads the state again; otherwise it comes up now.
                    NotificationCenter.getGlobalInstance().postNotificationName(ZgCustomer.consentChanged);
                } else {
                    ZenlyCustomer.acknowledgeDeletion(account);
                }
            }
            return;
        }
        rateLimited = false;
        switch (outcome) {
            case DONE:
                phase = Phase.DONE;
                ZgConsentWall.hold(currentAccount);
                break;
            case SEND:
            case RETRY:
                phase = Phase.WORKING;
                break;
            case FAILED:
                phase = Phase.FAILED;
                break;
            case NOT_LINKED:
                phase = Phase.NOT_LINKED;
                break;
            case RATE_LIMITED:
                phase = Phase.ERROR;
                errorText = R.string.ZenlyDeleteErrorRateLimited;
                rateLimited = true;
                break;
            case OFFLINE:
                phase = Phase.ERROR;
                errorText = R.string.ZenlyDeleteErrorOffline;
                break;
            case UNAVAILABLE:
            default:
                phase = Phase.ERROR;
                errorText = R.string.ZenlyDeleteErrorUnavailable;
                break;
        }
        update();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZGC_AccountDelete_Title);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (phase == Phase.DONE) {
            items.add(ZgCustomerCell.Factory.asField(ID_DONE, null, getString(R.string.ZenlyDeleteDoneTitle), getString(R.string.ZenlyDeleteDoneText)));
            items.add(UItem.asButton(ID_CLOSE, getString(R.string.Close)).accent());
            items.add(UItem.asShadow(null));
            return;
        }
        if (phase == Phase.NOT_LINKED) {
            // Nothing can be asked from here: the web page signs in with Telegram and erases the server account.
            items.add(UItem.asShadow(ID_TEXT, getString(R.string.ZGC_AccountDelete_NotLinked)));
            items.add(UItem.asButton(ID_OPEN_WEB, R.drawable.msg_link, getString(R.string.ZGC_AccountDelete_OpenWeb)).accent());
            items.add(UItem.asShadow(null));
            return;
        }
        final boolean billing = mentionsSubscription();
        items.add(UItem.asShadow(ID_TEXT, getString(R.string.ZGC_AccountDelete_Deleted) + "\n\n"
            + getString(R.string.ZGC_AccountDelete_Kept) + "\n\n"
            + getString(R.string.ZGC_AccountDelete_Telegram) + "\n\n"
            + getString(R.string.ZGC_AccountDelete_Timing)
            // Deleting never cancels a subscription: it keeps renewing until cancelled in Google Play.
            + (billing ? "\n\n" + LocaleController.formatString(R.string.ZenlyDeleteBilling, "Google Play") : "")));
        if (billing && (phase == Phase.IDLE || phase == Phase.FAILED)) {
            items.add(UItem.asButton(ID_MANAGE_SUBSCRIPTION, R.drawable.msg_openin, getString(R.string.ZenlyDeleteManageSubscription)).accent());
        }
        switch (phase) {
            case WORKING:
                items.add(ZgCustomerCell.Factory.asText(ID_STATE, getString(R.string.ZenlyDataConsentDeletePending), true, false));
                break;
            case PENDING:
                items.add(ZgCustomerCell.Factory.asText(ID_STATE, getString(R.string.ZenlyDataConsentDeletePending), false, false));
                items.add(UItem.asButton(ID_RETRY, getString(R.string.ZenlyMyDataRetry)).accent());
                break;
            case ERROR:
                items.add(ZgCustomerCell.Factory.asText(ID_STATE, getString(errorText), false, true));
                items.add(UItem.asButton(ID_RETRY, getString(R.string.ZenlyMyDataRetry)).accent());
                if (!rateLimited) {
                    items.add(UItem.asButton(ID_OPEN_WEB, R.drawable.msg_link, getString(R.string.ZGC_AccountDelete_OpenWeb)).accent());
                }
                break;
            case FAILED:
                items.add(ZgCustomerCell.Factory.asText(ID_STATE, getString(R.string.ZenlyDeleteFailed), false, true));
                items.add(UItem.asButton(ID_CONFIRM, R.drawable.msg_delete, getString(R.string.ZenlyDeleteConfirm)).red());
                items.add(UItem.asButton(ID_OPEN_WEB, R.drawable.msg_link, getString(R.string.ZGC_AccountDelete_OpenWeb)).accent());
                break;
            case IDLE:
            default:
                items.add(UItem.asButton(ID_CONFIRM, R.drawable.msg_delete, getString(R.string.ZenlyDeleteConfirm)).red());
                break;
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_CONFIRM:
                confirmDeletion();
                break;
            case ID_RETRY:
                send();
                break;
            case ID_OPEN_WEB:
                if (getParentActivity() != null) {
                    Browser.openInExternalBrowser(getParentActivity(), ZenlyConsent.accountDeleteUrl(ZgCustomer.defaultLocale()), false);
                }
                break;
            case ID_CLOSE:
                closeAfterDone();
                break;
            case ID_MANAGE_SUBSCRIPTION:
                openPlaySubscriptions();
                break;
        }
    }

    /**
     * Whether the screen speaks about a subscription: only the Google Play build (the only one that sells), while
     * Pro is a subscription or this account holds a paid period.
     */
    private boolean mentionsSubscription() {
        try {
            return org.telegram.zenlygram.pro.ZgPro.isPlayBuild()
                && (org.telegram.zenlygram.pro.ZgPro.isPaidMode() || org.telegram.zenlygram.pro.ZgPro.getAccessUntil(currentAccount) > 0);
        } catch (Throwable e) {
            return false;
        }
    }

    private void openPlaySubscriptions() {
        if (getParentActivity() == null) {
            return;
        }
        try {
            getParentActivity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_SUBSCRIPTIONS_URL)));
        } catch (ActivityNotFoundException e) {
            Browser.openInExternalBrowser(getParentActivity(), PLAY_SUBSCRIPTIONS_URL, false);
        }
    }

    /** The red action: one more confirmation, then the deletion. */
    private void confirmDeletion() {
        if ((phase != Phase.IDLE && phase != Phase.FAILED) || getParentActivity() == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
            .setTitle(getString(R.string.ZenlyDeleteConfirm2Title))
            .setMessage(getString(R.string.ZenlyDeleteConfirm2Text))
            .setNegativeButton(getString(R.string.Cancel), null)
            .setPositiveButton(getString(R.string.ZenlyDeleteConfirm2Action), (d, which) -> send())
            .create();
        if (showDialog(dialog) != null) {
            dialog.redPositive();
            final View positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setFilterTouchesWhenObscured(true); // a decision: never tapped through an overlay
            }
        }
    }

    /** Sends, or retries with the same key. */
    private void send() {
        if (destroyed || phase == Phase.WORKING || phase == Phase.DONE || phase == Phase.NOT_LINKED) {
            return;
        }
        phase = Phase.WORKING;
        // From here until the done text was read the wall stays down: it would cover the outcome the moment it lands.
        ZgConsentWall.hold(currentAccount);
        update();
        ZenlyCustomer.deleteAccount(currentAccount, this);
    }

    /** The done text was read: the erased account's record goes, and with it the consent record (state none). */
    private void acknowledge() {
        if (!acknowledged) {
            acknowledged = true;
            ZenlyCustomer.acknowledgeDeletion(currentAccount);
        }
    }

    /**
     * Close on the done text. Opened from the wall's decline or rights page, that page goes too, so the user is back on
     * the consent page; opened from Settings, the wall comes up by itself once this screen let it go.
     */
    private void closeAfterDone() {
        acknowledge();
        if (getParentLayout() != null) {
            final List<BaseFragment> stack = getParentLayout().getFragmentStack();
            final int index = stack == null ? -1 : stack.indexOf(this);
            if (index > 0 && stack.get(index - 1) instanceof ZgConsentDeclinedActivity) {
                stack.get(index - 1).removeSelfFromStack();
            }
        }
        finishFragment();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
