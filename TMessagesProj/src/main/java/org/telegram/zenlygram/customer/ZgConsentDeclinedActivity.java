/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.zenlygram.customer.ZenlyConsent;
import org.telegram.messenger.zenlygram.customer.ZenlyCustomer;
import org.telegram.messenger.zenlygram.tasks.ZenlyTasksSyncOwner;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.zenlygram.core.ZgConfig;

import java.util.ArrayList;

/**
 * Two pages of the iOS consent wall (SGConsentWallView), both usable without consenting:
 * <ul>
 *   <li><b>Decline page</b> (required mode, "I don't agree"): "Consent needed", the explanation, then Back (to the
 *       wall), Delete my Zenlygram account and data, Log out of this account and Use another account (only while
 *       another account is signed in); under "Data &amp; privacy": My data, Withdraw consent and the Privacy Policy.
 *       The decline is not written.</li>
 *   <li><b>Rights page</b> ("Data &amp; privacy" link): My data, Withdraw consent, Delete my Zenlygram account and
 *       data, Privacy Policy, Back.</li>
 * </ul>
 * Withdraw consent appears only while the server may still hold a grant of this account
 * ({@code ConsentSnapshot.withdrawable}). Links open outside the app: a browser screen would land under the wall.
 */
public class ZgConsentDeclinedActivity extends UniversalFragment {

    private static final int ID_BACK = 1;
    private static final int ID_LOGOUT = 2;
    private static final int ID_SWITCH = 3;
    private static final int ID_PRIVACY = 4;
    private static final int ID_MY_DATA = 5;
    private static final int ID_DELETE = 6;
    private static final int ID_WITHDRAW = 7;
    private static final int ID_TEXT = 8;
    private static final int ID_RIGHTS_HEADER = 9;

    private final boolean rightsOnly;

    public ZgConsentDeclinedActivity(boolean rightsOnly) {
        this.rightsOnly = rightsOnly;
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        NotificationCenter.getGlobalInstance().listen(view, ZgCustomer.consentChanged, args -> {
            if (listView != null) {
                listView.adapter.update(true);
            }
        });
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false); // back from My data or the deletion
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(rightsOnly ? R.string.ZGC_Settings_Data_Header : R.string.ZGC_Consent_Declined_Title);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean withdraw = ZgConsentFacts.offersWithdraw(ZgCustomer.snapshot(currentAccount), ZgCustomer.deletionBlocks(currentAccount));
        final boolean policy = ZgConfig.PRIVACY_POLICY_URL != null && ZgConfig.PRIVACY_POLICY_URL.startsWith("https://");
        if (rightsOnly) {
            items.add(UItem.asButton(ID_MY_DATA, R.drawable.msg_contacts, getString(R.string.ZGC_DataConsent_MyData)));
            if (withdraw) {
                items.add(UItem.asButton(ID_WITHDRAW, R.drawable.msg_leave, getString(R.string.ZGC_DataConsent_Withdraw)));
            }
            items.add(UItem.asButton(ID_DELETE, R.drawable.msg_delete, getString(R.string.ZGC_Consent_Declined_Delete)).red());
            if (policy) {
                items.add(UItem.asButton(ID_PRIVACY, R.drawable.msg_policy, getString(R.string.ZGC_Consent_Declined_PrivacyPolicy)));
            }
            items.add(UItem.asButton(ID_BACK, getString(R.string.ZGC_Consent_Declined_Back)).accent());
            items.add(UItem.asShadow(null));
            return;
        }
        items.add(UItem.asShadow(ID_TEXT, getString(R.string.ZGC_Consent_Declined_Text)));
        items.add(UItem.asButton(ID_BACK, getString(R.string.ZGC_Consent_Declined_Back)).accent());
        items.add(UItem.asButton(ID_DELETE, R.drawable.msg_delete, getString(R.string.ZGC_Consent_Declined_Delete)).red());
        items.add(UItem.asButton(ID_LOGOUT, R.drawable.msg_leave, getString(R.string.ZGC_Consent_Declined_Logout)).red());
        if (!otherAccounts().isEmpty()) {
            items.add(UItem.asButton(ID_SWITCH, R.drawable.msg_openprofile, getString(R.string.ZGC_Consent_Declined_SwitchAccount)));
        }
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(ID_RIGHTS_HEADER, getString(R.string.ZGC_Settings_Data_Header)));
        items.add(UItem.asButton(ID_MY_DATA, R.drawable.msg_contacts, getString(R.string.ZGC_DataConsent_MyData)));
        if (withdraw) {
            items.add(UItem.asButton(ID_WITHDRAW, R.drawable.msg_leave, getString(R.string.ZGC_DataConsent_Withdraw)));
        }
        if (policy) {
            items.add(UItem.asButton(ID_PRIVACY, R.drawable.msg_policy, getString(R.string.ZGC_Consent_Declined_PrivacyPolicy)));
        }
        items.add(UItem.asShadow(null));
    }

    /** The other signed-in account slots, in slot order. */
    private ArrayList<Integer> otherAccounts() {
        final ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a != currentAccount && UserConfig.getInstance(a).isClientActivated()) {
                accounts.add(a);
            }
        }
        return accounts;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_BACK:
                finishFragment();
                break;
            case ID_LOGOUT:
                confirmLogout();
                break;
            case ID_SWITCH:
                chooseAccount();
                break;
            case ID_PRIVACY:
                if (getParentActivity() != null) {
                    Browser.openInExternalBrowser(getParentActivity(), ZgConfig.PRIVACY_POLICY_URL, false);
                }
                break;
            case ID_MY_DATA:
                presentFragment(new ZgMyDataActivity());
                break;
            case ID_DELETE:
                presentFragment(new ZgAccountDeleteActivity());
                break;
            case ID_WITHDRAW:
                // The wall's own language is the app's; a withdrawal confirmed here goes back to the wall, which then
                // says so and shows where its delivery stands.
                ZgWithdrawFlow.confirm(this, currentAccount, ZgCustomer.defaultLocale(), () -> presentFragment(new ZgAccountDeleteActivity()),
                    this::finishFragment);
                break;
        }
    }

    /** "Use another account": the only other signed-in account at once, else a choice. */
    private void chooseAccount() {
        final ArrayList<Integer> accounts = otherAccounts();
        if (accounts.isEmpty() || getParentActivity() == null) {
            return;
        }
        if (accounts.size() == 1) {
            switchTo(accounts.get(0));
            return;
        }
        final CharSequence[] labels = new CharSequence[accounts.size()];
        for (int i = 0; i < labels.length; i++) {
            final String label = ZgCustomerFormat.accountLabel(UserConfig.getInstance(accounts.get(i)).getCurrentUser());
            labels[i] = label != null ? label : String.valueOf(accounts.get(i) + 1);
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
            .setTitle(getString(R.string.ZGC_Consent_Declined_SwitchAccount))
            .setItems(labels, (dialog, which) -> {
                if (which >= 0 && which < accounts.size()) {
                    switchTo(accounts.get(which));
                }
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .create());
    }

    private void switchTo(int account) {
        final Activity activity = getParentActivity();
        if (activity instanceof LaunchActivity && UserConfig.getInstance(account).isClientActivated()) {
            // LaunchActivity decides the wall again for the account it switched to.
            ((LaunchActivity) activity).switchToAccount(account, true);
        }
    }

    /**
     * Telegram's own logout question for THIS account, with what a logout leaves unsent (Cloud Tasks changes, a
     * withdrawal: sent once more when the device is linked, not deliverable from here when it is not) and the warning
     * that logging out of the only account removes the device's Zenlygram data (iOS SGCustomerAlert.logoutConfirmation).
     */
    private void confirmLogout() {
        if (getParentActivity() == null) {
            return;
        }
        final StringBuilder message = new StringBuilder(getString(R.string.AreYouSureLogout));
        final String tasks = ZenlyTasksSyncOwner.logoutNotice(getParentActivity(), currentAccount);
        if (!TextUtils.isEmpty(tasks)) {
            message.append("\n\n").append(tasks);
        }
        if (otherAccounts().isEmpty()) {
            message.append("\n\n").append(getString(R.string.ZGC_Consent_Declined_Logout_LastAccount));
        }
        final String withdrawal = ZenlyCustomer.logoutNotice(currentAccount);
        if (!TextUtils.isEmpty(withdrawal)) {
            message.append("\n\n").append(ZgCustomer.isLinked(currentAccount) ? withdrawal
                : getString(R.string.ZGC_DataConsent_Withdraw_PendingUpload) + "\n\n" + ZgCustomer.operatorLine());
        }
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
            .setTitle(getString(R.string.ZGC_Consent_Declined_Logout))
            .setMessage(message)
            .setPositiveButton(getString(R.string.LogOut), (d, w) -> MessagesController.getInstance(currentAccount).performLogout(1))
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        if (showDialog(dialog) != null) {
            dialog.redPositive();
            final View positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setFilterTouchesWhenObscured(true);
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
