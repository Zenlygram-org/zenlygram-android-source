/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.zenlygram.customer.ConsentMachine;
import org.telegram.messenger.zenlygram.customer.ConsentSnapshot;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyConsent;
import org.telegram.messenger.zenlygram.customer.ZenlyCustomer;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.zenlygram.core.ZgConfig;
import org.telegram.zenlygram.settings.ZgSettingRowCell;
import org.telegram.zenlygram.settings.ZgSettingsIcons.Tile;

import java.util.ArrayList;

/**
 * Settings → Zenlygram → Data &amp; consent of one Telegram account (iOS SGDataConsentView / SGDataConsentController):
 * the consent status — given (date and text version) or not sent yet, none, declined, withdrawn, a withdrawal being
 * sent / not delivered yet (Try again) / not deliverable from an unlinked device (the web page), review needed, link
 * ended, refused, deletion pending — then Consent text, My data, Give consent (while there is no usable grant),
 * Withdraw consent (while the server may hold one), Delete my Zenlygram account and data, the Privacy Policy, and the
 * operator, where the data is stored and that the screen is per Telegram account.
 *
 * <p>Everything shown is read from the cloud client ({@link ZgCustomer#snapshot}); the screen follows its consent
 * notification and the account's customer and link state changes. Reported as {@code zg.data_consent}.
 */
public class ZgDataConsentActivity extends UniversalFragment implements ZenlyStats.NamedScreen {

    private static final int ID_STATUS = 1;
    private static final int ID_READ = 2;
    private static final int ID_GRANT = 3;
    private static final int ID_WITHDRAW = 4;
    private static final int ID_MY_DATA = 5;
    private static final int ID_DELETE = 6;
    private static final int ID_PRIVACY = 7;
    private static final int ID_FOOTER = 8;
    private static final int ID_RETRY = 9;
    private static final int ID_WEB = 10;
    private static final int ID_NOTE = 11;
    private static final int ID_RESET_DEBUG = 12;

    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return CustomerEvents.Screen.ZG_DATA_CONSENT;
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
        setHasOwnBackground(true);
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        final Runnable refresh = () -> {
            if (listView != null) {
                listView.adapter.update(true);
            }
        };
        NotificationCenter.getGlobalInstance().listen(fragmentView, ZgCustomer.consentChanged, args -> refresh.run());
        // The account's readiness (an upload was confirmed) and its link (linked, unlinked, a link the server ended).
        NotificationCenter.getInstance(currentAccount).listen(fragmentView, NotificationCenter.zenlygramCustomerStateChanged, args -> refresh.run());
        NotificationCenter.getInstance(currentAccount).listen(fragmentView, NotificationCenter.zenlygramProStatusChanged, args -> refresh.run());
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(true); // back from giving consent, My data or the deletion
        }
    }

    private static UItem row(int id, int icon, Tile tile, int title) {
        return ZgSettingRowCell.Factory.asDisclosure(id, icon, tile.top, tile.bottom, getString(title), null, null, false, true);
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZGC_DataConsent_Title);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final ConsentSnapshot snapshot = ZgCustomer.snapshot(currentAccount);
        final boolean deletion = ZgCustomer.deletionBlocks(currentAccount);
        final ZgConsentFacts.Status status = ZgConsentFacts.status(snapshot, ZenlyPro.authority(), ZgCustomer.isLinked(currentAccount),
            ZgCustomer.isSending(currentAccount), deletion);

        items.add(ZgCustomerCell.Factory.asText(ID_STATUS, statusText(status, snapshot), status == ZgConsentFacts.Status.SENDING, false));
        if (status == ZgConsentFacts.Status.WITHDRAW_PENDING) {
            items.add(UItem.asButton(ID_RETRY, getString(R.string.ZenlyMyDataRetry)).accent());
        } else if (status == ZgConsentFacts.Status.WITHDRAW_NOT_LINKED) {
            items.add(UItem.asButton(ID_WEB, getString(R.string.ZGC_AccountDelete_OpenWeb)).accent());
        }
        // A withdrawal the server answered "superseded": the consent was given again on another device.
        items.add(UItem.asShadow(ID_NOTE, snapshot != null && snapshot.record.activeElsewhere
            ? getString(R.string.ZenlyDataConsentActiveElsewhere) : null));

        // The hub's row cell: its title wraps, so the long "Delete my Zenlygram account and data" label, which the
        // consent text quotes word for word, is never cut in any language.
        final int first = items.size();
        if (ZgCustomer.text() != null) {
            items.add(row(ID_READ, R.drawable.msg_report, Tile.GRAY, R.string.ZGC_DataConsent_ReadText));
        }
        items.add(row(ID_MY_DATA, R.drawable.msg_contact, Tile.BLUE, R.string.ZGC_DataConsent_MyData));
        if (ZgConsentFacts.offersGrant(snapshot, deletion) && ZgCustomer.canGrant()) {
            items.add(row(ID_GRANT, R.drawable.msg_select, Tile.GREEN, R.string.ZGC_DataConsent_Grant));
        }
        if (ZgConsentFacts.offersWithdraw(snapshot, deletion)) {
            items.add(row(ID_WITHDRAW, R.drawable.msg_leave, Tile.ORANGE, R.string.ZGC_DataConsent_Withdraw));
        }
        items.add(row(ID_DELETE, R.drawable.msg_delete, Tile.RED, R.string.ZGC_DataConsent_Delete));
        if (hasPrivacyPolicy()) {
            items.add(row(ID_PRIVACY, R.drawable.msg_policy, Tile.GRAY, R.string.ZGC_Settings_Data_PrivacyPolicy));
        }
        for (int i = first; i < items.size(); i++) {
            ZgSettingRowCell.Factory.setDivider(items.get(i), i + 1 < items.size());
        }
        items.add(UItem.asShadow(ID_FOOTER, ZgCustomer.operatorLine() + "\n\n" + getString(R.string.ZGC_DataConsent_Storage)
            + "\n\n" + getString(R.string.ZGC_DataConsent_Footer)));
        if (BuildVars.DEBUG_VERSION) {
            items.add(UItem.asButton(ID_RESET_DEBUG, getString(R.string.ZenlyDataConsentResetDebug)).red());
            items.add(UItem.asShadow(null));
        }
    }

    private static boolean hasPrivacyPolicy() {
        return ZgConfig.PRIVACY_POLICY_URL != null && ZgConfig.PRIVACY_POLICY_URL.startsWith("https://");
    }

    /** The status paragraph; dates are the decision's (the grant's, else the latest state change). */
    private static CharSequence statusText(ZgConsentFacts.Status status, ConsentSnapshot snapshot) {
        final ConsentMachine.Grant grant = snapshot == null ? null : snapshot.latestGrant();
        final String changed = ZgCustomerFormat.decisionDate(snapshot == null ? 0 : snapshot.record.changedAt);
        switch (status) {
            case GRANTED:
                return ZgCustomerFormat.sentence(formatString(R.string.ZGC_DataConsent_Status_Granted,
                    ZgCustomerFormat.decisionDate(grant == null ? 0 : grant.decidedAt), String.valueOf(grant == null ? 0 : grant.textVersion)));
            case PENDING:
                return getString(R.string.ZGC_DataConsent_Status_Pending);
            case DECLINED:
                return getString(R.string.ZGC_DataConsent_Status_Declined);
            case WITHDRAWN:
                return ZgCustomerFormat.sentence(formatString(R.string.ZGC_DataConsent_Status_Withdrawn, changed));
            case SENDING:
                return getString(R.string.ZGC_DataConsent_Withdraw_Sending);
            case WITHDRAW_PENDING:
                return ZgCustomerFormat.sentence(formatString(R.string.ZGC_DataConsent_Status_WithdrawPending, changed));
            case WITHDRAW_NOT_LINKED:
                return getString(R.string.ZGC_DataConsent_Withdraw_PendingUpload);
            case RECONSENT:
                return getString(R.string.ZenlyDataConsentStatusReconsent);
            case LINK_ENDED:
                return getString(R.string.ZenlyDataConsentStatusLinkEnded);
            case REJECTED:
                return getString(R.string.ZenlyDataConsentStatusRejected);
            case DELETION_PENDING:
                return getString(R.string.ZenlyDataConsentDeletePending);
            case NONE:
            default:
                return getString(R.string.ZGC_DataConsent_Status_None);
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_READ: {
                // The language the account agreed in, else the app's.
                final ConsentSnapshot snapshot = ZgCustomer.snapshot(currentAccount);
                final ConsentMachine.Grant grant = snapshot == null ? null : snapshot.latestGrant();
                presentFragment(new ZgConsentTextActivity(grant != null ? grant.locale : ZgCustomer.defaultLocale(), null));
                break;
            }
            case ID_GRANT:
                presentFragment(new ZgConsentWallActivity());
                break;
            case ID_WITHDRAW:
                ZgWithdrawFlow.confirm(this, currentAccount, ZgCustomer.defaultLocale(), () -> presentFragment(new ZgAccountDeleteActivity()), () -> {
                    if (listView != null) {
                        listView.adapter.update(true);
                    }
                });
                break;
            case ID_RETRY:
                ZgCustomer.retryWithdrawal(currentAccount);
                break;
            case ID_WEB:
                if (getParentActivity() != null) {
                    Browser.openInExternalBrowser(getParentActivity(), ZenlyConsent.accountDeleteUrl(ZgCustomer.defaultLocale()), false);
                }
                break;
            case ID_MY_DATA:
                presentFragment(new ZgMyDataActivity());
                break;
            case ID_DELETE:
                presentFragment(new ZgAccountDeleteActivity());
                break;
            case ID_PRIVACY:
                Browser.openUrl(getParentActivity(), ZgConfig.PRIVACY_POLICY_URL);
                break;
            case ID_RESET_DEBUG:
                resetDebug();
                break;
        }
    }

    /** Debug builds only: this device forgets the account's consent record (the server keeps what it received). */
    private void resetDebug() {
        final long tg = ZgCustomer.tg(currentAccount);
        if (tg <= 0 || !BuildVars.DEBUG_VERSION) {
            return;
        }
        ZenlyConsent.forget(tg);
        ZenlyCustomer.onConsentChanged(currentAccount);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
