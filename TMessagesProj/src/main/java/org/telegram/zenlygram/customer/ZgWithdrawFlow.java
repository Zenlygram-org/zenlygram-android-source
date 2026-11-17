/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.DialogInterface;

import org.telegram.messenger.R;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRepository;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * "Withdraw consent" as Data &amp; consent and the wall's rights rows ask it (iOS SGCustomerFlows.confirmWithdrawal,
 * SGCustomerAlert.withdrawConfirmation): the confirmation says what is deleted, how many Cloud Tasks changes that have
 * not reached the server would be lost (or that some may be, when they cannot be counted; nothing for none), what
 * happens in this build's mode and — when this device holds no link — that the withdrawal cannot be sent from here.
 * Cancel, "Delete my Zenlygram account and data" (opens the deletion, withdraws nothing) and Withdraw.
 *
 * <p>The withdrawal itself is the cloud client's ({@link ZgCustomer#withdraw}): it commits the state, purges the
 * statistics queue, discards the unsent Cloud Tasks changes and uploads the withdrawal when linked.
 */
final class ZgWithdrawFlow {

    /** The unsent changes could not be counted. */
    private static final int UNKNOWN = -1;

    private ZgWithdrawFlow() {
    }

    /**
     * @param textLocale    the language of the text or screen the user confirms on (uz / ru / en)
     * @param deleteInstead opens the deletion screen, or null to leave that button out
     * @param withdrew      runs after a withdrawal that was stored
     */
    static void confirm(final BaseFragment fragment, final int account, final String textLocale, final Runnable deleteInstead,
                        final Runnable withdrew) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        TasksCloudRepository.unsentChangeCount(account, (count, error) -> {
            // The count arrives on a later main-thread turn: the screen may be gone by then.
            if (fragment.getParentActivity() == null || fragment.getParentLayout() == null) {
                return;
            }
            show(fragment, account, textLocale, error != null || count == null || count < 0 ? UNKNOWN : count, deleteInstead, withdrew);
        });
    }

    /** The message of the confirmation (pure but for the strings): the parts in the iOS order, a blank line between. */
    static String message(int unsent, boolean required, boolean linked) {
        final StringBuilder message = new StringBuilder(getString(R.string.ZenlyDataConsentWithdrawText));
        if (unsent == UNKNOWN) {
            message.append("\n\n").append(getString(R.string.ZGC_DataConsent_Withdraw_Confirm_UnsentUnknown));
        } else if (unsent > 0) {
            message.append("\n\n").append(ZgCustomerFormat.unsentChanges(unsent));
        }
        message.append("\n\n").append(getString(required ? R.string.ZGC_DataConsent_Withdraw_Required : R.string.ZGC_DataConsent_Withdraw_Optional));
        if (!linked) {
            // "…or write to the contact shown below": the operator line follows it, so that is true in a dialog too.
            message.append("\n\n").append(getString(R.string.ZGC_DataConsent_Withdraw_PendingUpload))
                .append("\n\n").append(ZgCustomer.operatorLine());
        }
        return message.toString();
    }

    private static void show(final BaseFragment fragment, final int account, final String textLocale, int unsent, final Runnable deleteInstead,
                             final Runnable withdrew) {
        final Activity activity = fragment.getParentActivity();
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(getString(R.string.ZGC_DataConsent_Withdraw_Confirm_Title))
            .setMessage(message(unsent, ZgCustomer.isRequired(), ZgCustomer.isLinked(account)))
            .setPositiveButton(getString(R.string.ZGC_DataConsent_Withdraw_Confirm_Action), (d, which) -> {
                if (ZgCustomer.withdraw(account, textLocale)) {
                    if (withdrew != null) {
                        withdrew.run();
                    }
                } else if (fragment.getParentActivity() != null) {
                    // Nothing changed: the screens still show the previous state.
                    fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity())
                        .setMessage(getString(R.string.ZGC_Consent_Wall_SaveFailed))
                        .setPositiveButton(getString(R.string.OK), null)
                        .create());
                }
            })
            .setNegativeButton(getString(R.string.Cancel), null);
        if (deleteInstead != null) {
            builder.setNeutralButton(getString(R.string.ZenlyDataConsentWithdrawDeleteInstead), (d, which) -> deleteInstead.run());
        }
        final AlertDialog dialog = builder.create();
        if (fragment.showDialog(dialog) != null) {
            dialog.redPositive();
            final android.view.View positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setFilterTouchesWhenObscured(true); // a decision: never tapped through an overlay
            }
        }
    }
}
