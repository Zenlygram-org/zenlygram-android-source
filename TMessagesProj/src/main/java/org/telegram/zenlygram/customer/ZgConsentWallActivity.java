/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.zenlygram.customer.ConsentSnapshot;
import org.telegram.messenger.zenlygram.customer.ConsentText;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyConsent;
import org.telegram.messenger.zenlygram.customer.ZenlyCustomer;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.TypefaceSpan;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * The consent wall page (iOS SGConsentWallView, minimal wall of 2026-11-11), top to bottom: title, account line, the
 * state notice when there is one (why the wall asks again; after a withdrawal on this device also where its delivery
 * stands), the summary, "Consent text ›" (the text page with the language control), the age checkbox and its hint, the
 * mode note, I agree (needs the box), I don't agree, the save-failed line and the "Data &amp; privacy" link.
 *
 * <p>Required mode (this build): presented by {@link ZgConsentWall} over the signed-in account and not dismissable —
 * no back arrow, no swipe, Back sends the app to the background; "I don't agree" opens the decline page
 * ({@link ZgConsentDeclinedActivity}) and writes nothing. Optional mode: the same page, dismissable, a decline is
 * stored locally. {@code textUnavailable}: title, account, the message and the link only.
 *
 * <p>"I agree" is {@link ZgCustomer#grant} with exactly the text of the selected language as the cloud client renders
 * and hashes it; nothing waits on the network. The same page opens from Settings → Data &amp; consent → Give consent
 * (dismissable; there "I don't agree" just closes it in a required build).
 */
public class ZgConsentWallActivity extends UniversalFragment implements ZenlyStats.NamedScreen {

    private static final int ID_SUMMARY = 1;
    private static final int ID_TEXT = 2;
    private static final int ID_AGE = 3;
    private static final int ID_AGE_HINT = 4;
    private static final int ID_AGREE = 5;
    private static final int ID_DECLINE = 6;
    private static final int ID_NOTE = 7;
    private static final int ID_NOTICE = 8;
    private static final int ID_RIGHTS = 9;
    private static final int ID_DELIVERY = 10;
    private static final int ID_RETRY = 11;
    private static final int ID_WEB = 12;
    private static final int ID_SAVE_FAILED = 13;

    private String locale = ZgCustomer.defaultLocale();
    private boolean ageConfirmed;
    private boolean saveFailed;
    /**
     * This wall came up with the deleted notice. The erased account's record is acknowledged when the notice is shown
     * and the notice stays while this wall is up (iOS: "removed when shown; this wall keeps showing it").
     */
    private boolean showsDeletedNotice;
    private boolean closing;

    /** True when opened by ZgConsentWall, false when opened from Settings → Data &amp; consent. */
    private final boolean presentedByWall;

    public ZgConsentWallActivity() {
        this(false);
    }

    public ZgConsentWallActivity(boolean presentedByWall) {
        this.presentedByWall = presentedByWall;
    }

    boolean presentedByWall() {
        return presentedByWall;
    }

    boolean blocks() {
        return presentedByWall && ZgCustomer.isRequired();
    }

    /** Only the Settings page is a screen of the statistics (a wall is up exactly while nothing may be recorded). */
    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return presentedByWall ? null : CustomerEvents.Screen.ZG_DATA_CONSENT;
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        if (blocks()) {
            actionBar.setBackButtonDrawable(null);
            actionBar.setActionBarMenuOnItemClick(null);
        }
        NotificationCenter.getGlobalInstance().listen(view, ZgCustomer.consentChanged, args -> onConsentChanged());
        return view;
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        return !blocks() && super.isSwipeBackEnabled(event);
    }

    @Override
    public boolean canBeginSlide() {
        return !blocks() && super.canBeginSlide();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (blocks()) {
            if (invoked && getParentActivity() != null) {
                getParentActivity().moveTaskToBack(true);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public void onResume() {
        super.onResume();
        onConsentChanged();
    }

    /** The account's consent changed (or the page came back): leave when the wall is no longer needed, else show it. */
    private void onConsentChanged() {
        if (closing || getParentLayout() == null) {
            return;
        }
        if (presentedByWall && !ZgCustomer.shouldPresentWall(currentAccount)) {
            // Decided: here, in Settings, by another device's answer or by an account switch.
            close();
            return;
        }
        if (presentedByWall && !showsDeletedNotice
            && ZgCustomer.wallDecision(currentAccount).notice == ZgConsentWallPolicy.Notice.DELETED) {
            // Shown now, so the erased account's record goes (the client then reports the change: state none).
            showsDeletedNotice = true;
            ZenlyCustomer.acknowledgeDeletion(currentAccount);
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    /**
     * Leaves, once. removeSelfFromStack rather than finishFragment: the page may sit under its text or rights page when
     * the decision arrives, and on top it first ends a transition that is still running, where a plain finish would be
     * dropped and leave the wall up over an account that has agreed.
     */
    private void close() {
        if (closing || getParentLayout() == null) {
            return;
        }
        closing = true;
        removeSelfFromStack();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZGC_Consent_Wall_Title);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final String account = ZgCustomerFormat.accountLabel(UserConfig.getInstance(currentAccount).getCurrentUser());
        if (account != null) {
            items.add(UItem.asHeader(formatString(R.string.ZGC_Consent_Wall_Account, account)));
        }
        final ZgConsentWallPolicy.Decision decision = ZgCustomer.wallDecision(currentAccount);
        final boolean active = ZgCustomer.canGrant();
        final ConsentText.Shown shown = active ? ZgCustomer.render(locale) : null;
        final String age = shown == null ? null : ZgConsentMarkdown.ageStatement(shown.canonical);
        if (decision.presentation == ZgConsentWallPolicy.Presentation.TEXT_UNAVAILABLE || age == null) {
            // No text that could be agreed to: the message and the rights only (no summary, no Agree, no Decline).
            items.add(UItem.asShadow(ID_SUMMARY, getString(ZgCustomer.text() != null && !active
                ? R.string.ZGC_DataConsent_OperatorMissing : R.string.ZGC_Consent_Wall_TextUnavailable)));
            items.add(UItem.asButton(ID_RIGHTS, getString(R.string.ZGC_Settings_Data_Header)).accent());
            items.add(UItem.asShadow(null));
            return;
        }
        addNotice(items, decision.notice);
        items.add(UItem.asShadow(ID_SUMMARY, getString(R.string.ZGC_Consent_Wall_Summary)));
        items.add(UItem.asButton(ID_TEXT, R.drawable.msg_report, getString(R.string.ZGC_DataConsent_ReadText)).accent());
        items.add(ZgConsentAgeCell.Factory.of(ID_AGE, age, ageConfirmed));
        items.add(UItem.asShadow(ID_AGE_HINT, ageConfirmed ? null : getString(R.string.ZGC_Consent_Wall_AgeHint)));
        items.add(UItem.asShadow(ID_NOTE, getString(ZgCustomer.isRequired()
            ? R.string.ZGC_Consent_Wall_ModeNote_Required : R.string.ZGC_Consent_Wall_ModeNote_Optional)));
        items.add(UItem.asButton(ID_AGREE, getString(R.string.ZGC_Consent_Wall_Agree)).accent());
        items.add(UItem.asButton(ID_DECLINE, getString(R.string.ZGC_Consent_Wall_Decline)));
        if (saveFailed) {
            items.add(UItem.asShadow(ID_SAVE_FAILED, getString(R.string.ZGC_Consent_Wall_SaveFailed)));
        }
        items.add(UItem.asButton(ID_RIGHTS, getString(R.string.ZGC_Settings_Data_Header)));
        items.add(UItem.asShadow(null));
    }

    /** Why the wall asks (again), and after a withdrawal made on this device where its delivery stands. */
    private void addNotice(ArrayList<UItem> items, ZgConsentWallPolicy.Notice notice) {
        final ConsentSnapshot snapshot = ZgCustomer.snapshot(currentAccount);
        final String date = ZgCustomerFormat.decisionDate(snapshot == null ? 0 : snapshot.record.changedAt);
        if (showsDeletedNotice || notice == ZgConsentWallPolicy.Notice.DELETED) {
            final SpannableStringBuilder text = new SpannableStringBuilder(getString(R.string.ZenlyDeleteDoneTitle));
            text.setSpan(new TypefaceSpan(AndroidUtilities.bold()), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append("\n").append(getString(R.string.ZenlyDeleteDoneText));
            items.add(UItem.asShadow(ID_NOTICE, text));
            return;
        }
        switch (notice) {
            case RECONSENT:
            case OPERATOR_CHANGED:
                items.add(UItem.asShadow(ID_NOTICE, getString(R.string.ZGC_Consent_Wall_Notice_Reconsent)));
                break;
            case LINK_ENDED:
                items.add(UItem.asShadow(ID_NOTICE, getString(R.string.ZGC_Consent_Wall_Notice_LinkEnded)));
                break;
            case WITHDRAWN_REMOTE:
                items.add(UItem.asShadow(ID_NOTICE, ZgCustomerFormat.sentence(formatString(R.string.ZGC_Consent_Wall_Notice_WithdrawnRemote, date))));
                break;
            case WITHDRAWN:
                items.add(UItem.asShadow(ID_NOTICE, ZgCustomerFormat.sentence(formatString(R.string.ZGC_Consent_Wall_Notice_Withdrawn, date))));
                switch (ZgCustomer.delivery(currentAccount)) {
                    case SENDING:
                        items.add(ZgCustomerCell.Factory.asText(ID_DELIVERY, getString(R.string.ZGC_DataConsent_Withdraw_Sending), true, false));
                        break;
                    case PENDING:
                        items.add(UItem.asShadow(ID_DELIVERY, ZgCustomerFormat.sentence(formatString(R.string.ZGC_DataConsent_Status_WithdrawPending, date))));
                        items.add(UItem.asButton(ID_RETRY, getString(R.string.ZenlyMyDataRetry)).accent());
                        break;
                    case NOT_LINKED:
                        // "…or write to the contact shown below": the operator line follows it here.
                        items.add(UItem.asShadow(ID_DELIVERY, getString(R.string.ZGC_DataConsent_Withdraw_PendingUpload) + "\n\n" + ZgCustomer.operatorLine()));
                        items.add(UItem.asButton(ID_WEB, getString(R.string.ZGC_AccountDelete_OpenWeb)).accent());
                        break;
                    case DELIVERED:
                    default:
                        break;
                }
                break;
            case NONE:
            default:
                break;
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_TEXT:
                presentFragment(new ZgConsentTextActivity(locale, value -> {
                    locale = value;
                    if (listView != null) {
                        listView.adapter.update(false);
                    }
                }));
                break;
            case ID_AGE:
                ageConfirmed = !ageConfirmed;
                listView.adapter.update(true);
                break;
            case ID_AGREE:
                if (!ageConfirmed) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.ZGC_Consent_Wall_AgeHint)).show();
                    return;
                }
                // Exactly the text of the selected language as it is rendered now: its hash is what the grant records.
                if (ZgCustomer.grant(currentAccount, ZgCustomer.render(locale))) {
                    close(); // unless the change the grant posted has closed the wall already
                } else {
                    saveFailed = true;
                    listView.adapter.update(true);
                }
                break;
            case ID_DECLINE:
                if (presentedByWall && ZgCustomer.isRequired()) {
                    presentFragment(new ZgConsentDeclinedActivity(false));
                } else if (ZgCustomer.decline(currentAccount)) {
                    // Settings in a required build: nothing is written, the page closes. Optional: declined, then closes.
                    close();
                } else {
                    saveFailed = true;
                    listView.adapter.update(true);
                }
                break;
            case ID_RIGHTS:
                presentFragment(new ZgConsentDeclinedActivity(true));
                break;
            case ID_RETRY:
                ZgCustomer.retryWithdrawal(currentAccount);
                break;
            case ID_WEB:
                if (getParentActivity() != null) {
                    Browser.openInExternalBrowser(getParentActivity(), ZenlyConsent.accountDeleteUrl(ZgCustomer.defaultLocale()), false);
                }
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
