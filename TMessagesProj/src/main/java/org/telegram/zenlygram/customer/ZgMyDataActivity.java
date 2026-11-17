/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyCustomer;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * «Mening maʼlumotlarim» / My data (iOS SGMyDataView): what our server holds for this Telegram account, read with
 * {@code GET /v1/profile/me} on the account's own link — asked by the user, in every consent state (the data subject's
 * own channel) — and shown read-only ({@link ZgMyData}). Kept in memory only; times in the device's time zone.
 *
 * <p>The request is the cloud client's ({@code ZenlyCustomer.loadMyData}): an account this device has not linked gets
 * the not-linked notice and nothing is asked; an error shows its text with Try again. One request at a time; an answer
 * that arrives after the screen closed or was reloaded is dropped. Reported as {@code zg.my_data}.
 */
public class ZgMyDataActivity extends UniversalFragment implements ZenlyStats.NamedScreen {

    private static final int ID_LOADING = 1;
    private static final int ID_NOTICE = 2;
    private static final int ID_RETRY = 3;
    /** Rows of the answer: section titles from here, fields after them. */
    private static final int ID_SECTIONS = 1000;

    private enum State { LOADING, NOT_LINKED, FAILED, LOADED }

    private State state = State.LOADING;
    private int errorText = R.string.ZenlyMyDataUnavailable;
    private List<ZgMyData.Section> sections;
    private boolean loading;
    private boolean destroyed;
    /** Fences an answer of an earlier load. */
    private int generation;

    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return CustomerEvents.Screen.ZG_MY_DATA;
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
        load();
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        generation++;
        super.onFragmentDestroy();
    }

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        final int expected = ++generation;
        show(State.LOADING);
        ZenlyCustomer.loadMyData(currentAccount, data -> {
            if (destroyed || expected != generation) {
                return;
            }
            loading = false;
            if (data != null && data.body != null) {
                sections = ZgMyData.sections(data.body, TEXTS);
                if (sections == null || sections.isEmpty()) {
                    errorText = R.string.ZenlyMyDataUnavailable;
                    show(State.FAILED);
                } else {
                    show(State.LOADED);
                }
            } else {
                errorText = errorText(data == null ? null : data.error);
                show(errorText == R.string.ZGC_MyData_NotLinked ? State.NOT_LINKED : State.FAILED);
            }
        });
    }

    private void show(State next) {
        state = next;
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    /** The text of the client's stable error code. */
    static int errorText(String error) {
        if (error == null) {
            return R.string.ZenlyMyDataUnavailable;
        }
        switch (error) {
            case "link_required":
            case "login_required":
            case "profile_account_not_linked":
                return R.string.ZGC_MyData_NotLinked;
            case "rate_limited":
                return R.string.ZenlyMyDataErrorRateLimited;
            case "connection_failed":
            case "offline":
            case "timeout":
            case "dns":
            case "tls":
                return R.string.ZenlyMyDataErrorOffline;
            case "server_error":
            case "service_unavailable":
            case "maintenance":
                return R.string.ZenlyMyDataErrorUnavailable;
            default:
                return R.string.ZenlyMyDataUnavailable;
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZGC_MyData_Title);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        switch (state) {
            case LOADING:
                items.add(ZgCustomerCell.Factory.asText(ID_LOADING, getString(R.string.ZenlyMyDataLoading), true, false));
                items.add(UItem.asShadow(null));
                return;
            case NOT_LINKED:
                items.add(UItem.asShadow(ID_NOTICE, getString(R.string.ZGC_MyData_NotLinked)));
                return;
            case FAILED:
                items.add(UItem.asShadow(ID_NOTICE, getString(errorText)));
                items.add(UItem.asButton(ID_RETRY, getString(R.string.ZenlyMyDataRetry)).accent());
                items.add(UItem.asShadow(null));
                return;
            case LOADED:
            default:
                break;
        }
        int id = ID_SECTIONS;
        for (ZgMyData.Section section : sections) {
            if (section.title != null) {
                items.add(UItem.asHeader(id++, getString(label(section.title))));
            } else {
                id++;
            }
            for (ZgMyData.Row row : section.rows) {
                items.add(ZgCustomerCell.Factory.asField(id++, row.label != null ? getString(label(row.label)) : row.rawLabel, row.value, row.detail));
            }
            items.add(UItem.asShadow(id++, null));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            load();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private static int label(ZgMyData.Label label) {
        switch (label) {
            case ACCOUNT:
                return R.string.ZenlyMyDataSectionAccount;
            case CONSENT:
                return R.string.ZenlyMyDataSectionConsent;
            case HISTORY:
                return R.string.ZenlyMyDataSectionHistory;
            case PROFILE:
                return R.string.ZenlyMyDataSectionProfile;
            case DEVICES:
                return R.string.ZenlyMyDataSectionDevices;
            case ACTIVITY:
                return R.string.ZenlyMyDataSectionActivity;
            case EVENTS:
                return R.string.ZenlyMyDataSectionEvents;
            case SUBSCRIPTION:
                return R.string.ZenlyMyDataSectionSubscription;
            case RETENTION:
                return R.string.ZenlyMyDataSectionRetention;
            case ID:
                return R.string.ZenlyMyDataFieldId;
            case TELEGRAM_ID:
                return R.string.ZenlyMyDataFieldTelegramId;
            case CREATED:
                return R.string.ZenlyMyDataFieldCreated;
            case PHONE:
                return R.string.ZenlyMyDataFieldPhone;
            case COUNTRY:
                return R.string.ZenlyMyDataFieldCountry;
            case USERNAMES:
                return R.string.ZenlyMyDataFieldUsernames;
            case FIRST_NAME:
                return R.string.ZenlyMyDataFieldFirstName;
            case LAST_NAME:
                return R.string.ZenlyMyDataFieldLastName;
            case LANGUAGE:
                return R.string.ZenlyMyDataFieldLanguage;
            case PREMIUM:
                return R.string.ZenlyMyDataFieldPremium;
            case MODEL:
                return R.string.ZenlyMyDataFieldModel;
            case OS:
                return R.string.ZenlyMyDataFieldOs;
            case APP:
                return R.string.ZenlyMyDataFieldApp;
            case LOCALE:
                return R.string.ZenlyMyDataFieldLocale;
            case TIME_ZONE:
                return R.string.ZenlyMyDataFieldTimeZone;
            case DISTRIBUTION:
                return R.string.ZenlyMyDataFieldDistribution;
            case FIRST_SEEN:
                return R.string.ZenlyMyDataFieldFirstSeen;
            case LAST_SEEN:
                return R.string.ZenlyMyDataFieldLastSeen;
            case FIRST_ACTIVE:
                return R.string.ZenlyMyDataFieldFirstActive;
            case LAST_ACTIVE:
                return R.string.ZenlyMyDataFieldLastActive;
            case ACTIVE_DAYS:
                return R.string.ZenlyMyDataFieldActiveDays;
            case SESSIONS_30:
                return R.string.ZenlyMyDataFieldSessions30;
            case SECONDS_30:
                return R.string.ZenlyMyDataFieldSeconds30;
            case EVENTS_TOTAL:
                return R.string.ZenlyMyDataEventsTotal;
            case RETAINED:
            default:
                return R.string.ZenlyMyDataFieldRetained;
        }
    }

    /** The app-language texts of the rows. */
    private static final ZgMyData.Texts TEXTS = new ZgMyData.Texts() {
        @Override
        public String time(String instant) {
            return ZgCustomerFormat.serverTime(instant);
        }

        @Override
        public String duration(long seconds) {
            return ZgCustomerFormat.duration(seconds);
        }

        @Override
        public String days(int count) {
            return formatPluralString("Days", count);
        }

        @Override
        public String months(int count) {
            return formatPluralString("Months", count);
        }

        @Override
        public String years(int count) {
            return formatPluralString("Years", count);
        }

        @Override
        public String consentState(String state) {
            if (state == null || "none".equals(state)) {
                return getString(R.string.ZGC_Settings_Data_State_None);
            }
            final String action = consentAction("granted".equals(state) ? "grant" : "withdrawn".equals(state) ? "withdraw" : null);
            return action != null ? action : state;
        }

        @Override
        public String consentAction(String action) {
            if ("grant".equals(action)) {
                return getString(R.string.ZGC_Settings_Data_State_Granted);
            }
            if ("withdraw".equals(action)) {
                return getString(R.string.ZGC_Settings_Data_State_Withdrawn);
            }
            return null;
        }

        @Override
        public String source(String source) {
            switch (String.valueOf(source)) {
                case "telegram_attest":
                    return getString(R.string.ZenlyMyDataSourceTelegramAttest);
                case "telegram_bot":
                    return getString(R.string.ZenlyMyDataSourceTelegramBot);
                case "client":
                    return getString(R.string.ZenlyMyDataSourceClient);
                case "telegram_session":
                    return getString(R.string.ZenlyMyDataSourceTelegramSession);
                default:
                    return null;
            }
        }

        @Override
        public String privacyEvents(int count) {
            return formatString(R.string.ZenlyMyDataPrivacyFeatures, count);
        }
    };
}
