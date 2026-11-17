/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import org.telegram.messenger.zenlygram.customer.ConsentMachine;
import org.telegram.messenger.zenlygram.customer.ConsentSnapshot;

/**
 * What the customer screens show for one account, derived from the cloud client's consent snapshot
 * ({@link ConsentSnapshot}) and the few facts beside it (linked, a deletion, a withdrawal being sent). The client
 * decides what may be sent; this only reads its state into the shapes the iOS screens use: the wall policy's inputs
 * (SGCustomerFlows.wallInputs), the Settings row value (SGCustomerSync.ConsentSummary), the status of Data &amp; consent
 * (SGDataConsentStatus) and a withdrawal's delivery (SGWithdrawalDelivery). Pure Java, tested on the JVM
 * (ZgConsentFactsTest).
 */
final class ZgConsentFacts {

    private ZgConsentFacts() {
    }

    // -----------------------------------------------------------------------------------------------------------
    // The wall
    // -----------------------------------------------------------------------------------------------------------

    /**
     * The inputs of {@link ZgConsentWallPolicy#decide} for {@code snapshot} (never null here: a slot without a user is
     * not asked). The policy judges the STORED state plus the facts the client derives on top of it, so a grant whose
     * link the server ended reads row 3 and one whose text version was retired reads row 5, like iOS; the client
     * itself calls both needs_reconsent ({@link ConsentSnapshot#effectiveState}), and the two agree on every state
     * (ZgConsentFactsTest.wallAgreesWithTheClient).
     *
     * @param renderedSha256ForRecord this build's hash of the stored grant's text (version and locale), or null when
     *                                the record is not a stored grant or that version is not bundled
     * @param deletedNotice           this device erased the account and its done text was not shown yet
     */
    static ZgConsentWallPolicy.Inputs wallInputs(boolean required, boolean readable, ConsentSnapshot snapshot, int bundledCurrentVersion,
                                                 int bundledMinValidVersion, String renderedSha256ForRecord, boolean deletedNotice) {
        final ZgConsentWallPolicy.Inputs in = new ZgConsentWallPolicy.Inputs();
        in.required = required;
        in.readable = readable;
        in.deletedNotice = deletedNotice;
        in.bundledCurrentVersion = bundledCurrentVersion;
        in.bundledMinValidVersion = bundledMinValidVersion;
        final ConsentMachine.Record record = snapshot.record;
        in.state = record.state.wire;
        if (record.grant != null) {
            in.recordTextVersion = record.grant.textVersion;
            in.recordTextSha256 = record.grant.textSha256;
        }
        if (record.state == ConsentMachine.State.NEEDS_RECONSENT) {
            // The client stores two causes; a text this build renders differently is stored as "reconsent" there, and
            // the wall words both the same way.
            in.storedNotice = record.cause == ConsentMachine.Notice.LINK_ENDED
                ? ZgConsentWallPolicy.StoredNotice.LINK_ENDED : ZgConsentWallPolicy.StoredNotice.RECONSENT;
        } else if (record.state == ConsentMachine.State.GRANTED) {
            in.linkEnded = snapshot.effectiveState() == ConsentMachine.State.NEEDS_RECONSENT
                && snapshot.notice() == ConsentMachine.Notice.LINK_ENDED;
            in.latestGrantRejection = snapshot.latestRejectedCode();
            in.renderedSha256ForRecord = renderedSha256ForRecord;
        }
        return in;
    }

    // -----------------------------------------------------------------------------------------------------------
    // Settings row
    // -----------------------------------------------------------------------------------------------------------

    /** The value of the Settings row: Agreed / Not agreed / Declined / Withdrawn / Review needed. */
    enum Summary {
        GRANTED, NONE, DECLINED, WITHDRAWN,
        /** The grant must be given again: needs_reconsent, a refused grant, a link the server ended. */
        REVIEW
    }

    static Summary summary(ConsentSnapshot snapshot) {
        if (snapshot == null) {
            return Summary.NONE;
        }
        switch (snapshot.effectiveState()) {
            case GRANTED:
                return snapshot.latestRejected() ? Summary.REVIEW : Summary.GRANTED;
            case DECLINED:
                return Summary.DECLINED;
            case WITHDRAWN:
            case WITHDRAWN_REMOTE:
                return Summary.WITHDRAWN;
            case NEEDS_RECONSENT:
                return Summary.REVIEW;
            case NONE:
            default:
                return Summary.NONE;
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Data & consent
    // -----------------------------------------------------------------------------------------------------------

    /** Where a withdrawal made on this device stands. */
    enum Delivery {
        /** The server acknowledged it (or there is nothing to deliver). */
        DELIVERED,
        /** The send the user asked for is under way (shown for up to 10 s). */
        SENDING,
        /** Not acknowledged yet while this device is linked: the client keeps trying, and the user may retry. */
        PENDING,
        /** This device holds no link: it cannot be sent from here (the web page instead). */
        NOT_LINKED
    }

    /**
     * @param authority the configured origin, or null (then nothing waits for it)
     * @param sending   the user started or retried the send less than 10 s ago
     */
    static Delivery delivery(ConsentSnapshot snapshot, String authority, boolean linked, boolean sending) {
        if (snapshot == null || snapshot.effectiveState() != ConsentMachine.State.WITHDRAWN || authority == null
            || !snapshot.pendingWithdraw(authority)) {
            return Delivery.DELIVERED;
        }
        if (!linked) {
            return Delivery.NOT_LINKED;
        }
        return sending ? Delivery.SENDING : Delivery.PENDING;
    }

    /** The status paragraph of Data &amp; consent. */
    enum Status {
        /** A grant the server acknowledged: its date and text version. */
        GRANTED,
        /** A grant not acknowledged yet. */
        PENDING,
        NONE,
        DECLINED,
        /** An acknowledged withdrawal, or one made on another device: its date. */
        WITHDRAWN,
        SENDING,
        /** + Retry. */
        WITHDRAW_PENDING,
        /** + the web page. */
        WITHDRAW_NOT_LINKED,
        RECONSENT,
        LINK_ENDED,
        /** The server refused the latest grant. */
        REJECTED,
        /** An account deletion runs or waits for a retry: nothing else applies until it ends. */
        DELETION_PENDING
    }

    static Status status(ConsentSnapshot snapshot, String authority, boolean linked, boolean sending, boolean deletionBlocks) {
        if (deletionBlocks) {
            return Status.DELETION_PENDING;
        }
        if (snapshot == null) {
            return Status.NONE;
        }
        switch (snapshot.effectiveState()) {
            case GRANTED: {
                if (snapshot.latestRejected()) {
                    return Status.REJECTED;
                }
                final ConsentMachine.Grant grant = snapshot.latestGrant();
                return grant != null && authority != null && snapshot.isAcked(authority, grant.consentId) ? Status.GRANTED : Status.PENDING;
            }
            case DECLINED:
                return Status.DECLINED;
            case WITHDRAWN:
                switch (delivery(snapshot, authority, linked, sending)) {
                    case SENDING:
                        return Status.SENDING;
                    case PENDING:
                        return Status.WITHDRAW_PENDING;
                    case NOT_LINKED:
                        return Status.WITHDRAW_NOT_LINKED;
                    case DELIVERED:
                    default:
                        return Status.WITHDRAWN;
                }
            case WITHDRAWN_REMOTE:
                return Status.WITHDRAWN;
            case NEEDS_RECONSENT:
                return snapshot.notice() == ConsentMachine.Notice.LINK_ENDED ? Status.LINK_ENDED : Status.RECONSENT;
            case NONE:
            default:
                return Status.NONE;
        }
    }

    /** "Give consent" is offered: no usable grant, and no deletion under way. */
    static boolean offersGrant(ConsentSnapshot snapshot, boolean deletionBlocks) {
        return snapshot != null && snapshot.grantable() && !deletionBlocks;
    }

    /** "Withdraw consent" is offered: the server may still hold a grant of this account, and no deletion is under way. */
    static boolean offersWithdraw(ConsentSnapshot snapshot, boolean deletionBlocks) {
        return snapshot != null && snapshot.withdrawable() && !deletionBlocks;
    }
}
