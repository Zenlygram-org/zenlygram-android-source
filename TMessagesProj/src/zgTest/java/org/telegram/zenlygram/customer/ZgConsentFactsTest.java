/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.messenger.zenlygram.customer.ConsentMachine;
import org.telegram.messenger.zenlygram.customer.ConsentManifest;
import org.telegram.messenger.zenlygram.customer.ConsentSnapshot;
import org.telegram.messenger.zenlygram.customer.ConsentText;
import org.telegram.zenlygram.customer.ZgConsentFacts.Delivery;
import org.telegram.zenlygram.customer.ZgConsentFacts.Status;
import org.telegram.zenlygram.customer.ZgConsentFacts.Summary;
import org.telegram.zenlygram.customer.ZgConsentWallPolicy.Decision;
import org.telegram.zenlygram.customer.ZgConsentWallPolicy.Notice;
import org.telegram.zenlygram.customer.ZgConsentWallPolicy.Presentation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The facade's reading of the cloud client's consent state: every state the client's own machine produces (grant,
 * withdrawal here and elsewhere, re-consent, a link the server ended, a stale or refused grant, a retired text
 * version) is mapped to the iOS wall decision, the Settings row value and the Data &amp; consent status, and the wall
 * is up exactly when the client itself says the account has no consent in effect.
 */
public class ZgConsentFactsTest {

    private static final String AUTH = "zenlygram.org";
    private static final long TG = 5550001L;
    private static final long T0 = 1_780_000_000L;
    private static final String HASH = hex('a');
    private static final ConsentText.Shown TEXT = new ConsentText.Shown(1, ConsentManifest.SCOPES, "uz", "text", HASH);

    private static String hex(char c) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            out.append(c);
        }
        return out.toString();
    }

    private static String id(int n) {
        return String.format(java.util.Locale.ROOT, "00000000-0000-4000-9000-%012d", n);
    }

    private static ConsentSnapshot none() {
        return ConsentSnapshot.empty(TG, 1);
    }

    private static ConsentSnapshot granted() {
        return ConsentMachine.grant(none(), TEXT, ConsentMachine.Mode.REQUIRED, id(1), T0, "13.2.1", AUTH).snapshot;
    }

    /** The grant, acknowledged by the server at AUTH. */
    private static ConsentSnapshot acked() {
        final ConsentSnapshot granted = granted();
        return ConsentMachine.consentsAnswered(granted, AUTH,
            Collections.singletonList(new ConsentMachine.Result(id(1), "recorded", false)),
            new ConsentMachine.Effective("granted", id(1), 1, T0), T0 + 1).snapshot;
    }

    private static ConsentSnapshot withdrawn(ConsentSnapshot from) {
        return ConsentMachine.withdraw(from, ConsentMachine.Mode.REQUIRED, "uz", id(2), T0 + 10, "13.2.1", AUTH).snapshot;
    }

    private static ConsentSnapshot withdrawnAcked() {
        final ConsentSnapshot withdrawn = withdrawn(acked());
        return ConsentMachine.consentsAnswered(withdrawn, AUTH,
            Collections.singletonList(new ConsentMachine.Result(id(2), "recorded", false)),
            new ConsentMachine.Effective("withdrawn", id(2), 1, T0 + 10), T0 + 11).snapshot;
    }

    private static ConsentSnapshot withdrawnRemote() {
        return ConsentMachine.consentRequired(granted(), "withdrawn", T0 + 100, T0 + 200).snapshot;
    }

    private static ConsentSnapshot reconsentAsked() {
        return ConsentMachine.consentRequired(granted(), "reconsent_required", 0, T0 + 200).snapshot;
    }

    /** A binding loss after the grant, not stored yet: the record still says granted. */
    private static ConsentSnapshot lossAfterGrant() {
        return ConsentMachine.bindingLoss(granted(), T0 + 5).snapshot;
    }

    private static ConsentSnapshot linkEndedStored() {
        return ConsentMachine.linkEnded(lossAfterGrant()).snapshot;
    }

    private static ConsentSnapshot stale() {
        final ConsentSnapshot granted = granted();
        return ConsentMachine.consentStale(granted, granted.latestGrantJson(), T0 + 5).snapshot;
    }

    private static ConsentSnapshot operatorChangedStored() {
        return ConsentMachine.operatorChanged(granted(), hex('b'), T0 + 1).snapshot;
    }

    private static ConsentSnapshot rejected(String code) {
        return ConsentMachine.rejected(granted(), Collections.singletonList(id(1)), code).snapshot;
    }

    private static ConsentSnapshot declinedOptional() {
        return ConsentMachine.decline(none(), ConsentMachine.Mode.OPTIONAL, T0).snapshot;
    }

    private static Decision decide(boolean required, ConsentSnapshot snapshot) {
        return decide(required, snapshot, 1, false);
    }

    /** As ZgCustomer.wallDecision feeds the policy: this build renders a stored grant's text with the same hash. */
    private static Decision decide(boolean required, ConsentSnapshot snapshot, int bundledCurrent, boolean deleted) {
        final boolean storedGrant = snapshot.record.state == ConsentMachine.State.GRANTED;
        return ZgConsentWallPolicy.decide(ZgConsentFacts.wallInputs(required, true, snapshot, bundledCurrent, snapshot.minValidVersion,
            storedGrant ? HASH : null, deleted));
    }

    private static void expect(Decision decision, Presentation presentation, Notice notice) {
        assertEquals(decision.toString(), presentation, decision.presentation);
        assertEquals(decision.toString(), notice, decision.notice);
    }

    @Test
    public void everyWallStateOfTheRequiredBuild() {
        expect(decide(true, none()), Presentation.WALL, Notice.NONE);
        expect(decide(true, granted()), Presentation.NONE, Notice.NONE);
        expect(decide(true, acked()), Presentation.NONE, Notice.NONE);
        expect(decide(true, withdrawn(granted())), Presentation.WALL, Notice.WITHDRAWN);
        expect(decide(true, withdrawnAcked()), Presentation.WALL, Notice.WITHDRAWN);
        expect(decide(true, withdrawnRemote()), Presentation.WALL, Notice.WITHDRAWN_REMOTE);
        expect(decide(true, reconsentAsked()), Presentation.WALL, Notice.RECONSENT);
        expect(decide(true, operatorChangedStored()), Presentation.WALL, Notice.RECONSENT);
        // The server ended the link: derived from the recorded loss, then stored, or a grant bind/start refused as stale.
        expect(decide(true, lossAfterGrant()), Presentation.WALL, Notice.LINK_ENDED);
        expect(decide(true, linkEndedStored()), Presentation.WALL, Notice.LINK_ENDED);
        expect(decide(true, stale()), Presentation.WALL, Notice.LINK_ENDED);
        // A later link clears the loss; the ended grant stays ended.
        expect(decide(true, ConsentMachine.bindingLoss(linkEndedStored(), 0).snapshot), Presentation.WALL, Notice.LINK_ENDED);
        // A loss before the grant does not end it.
        expect(decide(true, ConsentMachine.bindingLoss(granted(), T0 - 5).snapshot), Presentation.NONE, Notice.NONE);
        // The bundled min_valid_version moved past the granted version.
        expect(decide(true, granted().withMinValidVersion(2), 2, false), Presentation.WALL, Notice.RECONSENT);
        // A declined record of an optional build never bypasses a required one.
        expect(decide(true, declinedOptional()), Presentation.WALL, Notice.NONE);
    }

    @Test
    public void thisBuildRendersTheGrantedTextDifferently() {
        // Before the client's launch pass stored it: the policy sees the other hash itself (iOS row 6).
        final ConsentSnapshot granted = granted();
        final Decision decision = ZgConsentWallPolicy.decide(ZgConsentFacts.wallInputs(true, true, granted, 1, 1, hex('b'), false));
        expect(decision, Presentation.WALL, Notice.OPERATOR_CHANGED);
        // A version this build does not bundle is not compared.
        expect(ZgConsentWallPolicy.decide(ZgConsentFacts.wallInputs(true, true, granted, 1, 1, null, false)), Presentation.NONE, Notice.NONE);
    }

    @Test
    public void refusedGrants() {
        // Another refusal stays usable on the wall; Settings shows it (iOS row 7).
        expect(decide(true, rejected("consent_text_mismatch")), Presentation.NONE, Notice.NONE);
        // The server retired the granted version: this build's own text is the retired one, so there is nothing to agree to...
        expect(decide(true, rejected(ZgConsentWallPolicy.VERSION_RETIRED_CODE), 1, false), Presentation.TEXT_UNAVAILABLE, Notice.NONE);
        // ...unless this build bundles a newer one.
        expect(decide(true, rejected(ZgConsentWallPolicy.VERSION_RETIRED_CODE), 2, false), Presentation.WALL, Notice.RECONSENT);
    }

    @Test
    public void deletedNoticeAndUnreadableStore() {
        expect(decide(true, granted(), 1, true), Presentation.WALL, Notice.DELETED);
        expect(decide(true, none(), 1, true), Presentation.WALL, Notice.DELETED);
        expect(decide(false, none(), 1, true), Presentation.NONE, Notice.NONE);
        // Not started yet (the earlier store was not moved): nothing is shown or written.
        final Decision deferred = ZgConsentWallPolicy.decide(ZgConsentFacts.wallInputs(true, false, none(), 1, 1, null, false));
        expect(deferred, Presentation.DEFERRED, Notice.NONE);
        assertFalse(deferred.blocks());
    }

    @Test
    public void optionalBuild() {
        expect(decide(false, none()), Presentation.PROMPT, Notice.NONE);
        expect(decide(false, declinedOptional()), Presentation.NONE, Notice.NONE);
        expect(decide(false, granted()), Presentation.NONE, Notice.NONE);
        expect(decide(false, withdrawn(granted())), Presentation.NONE, Notice.NONE);
        expect(decide(false, withdrawnRemote()), Presentation.NONE, Notice.NONE);
        expect(decide(false, reconsentAsked()), Presentation.PROMPT, Notice.RECONSENT);
        expect(decide(false, lossAfterGrant()), Presentation.PROMPT, Notice.LINK_ENDED);
        expect(decide(false, linkEndedStored()), Presentation.PROMPT, Notice.LINK_ENDED);
    }

    /**
     * The wall and the client's gate agree: in a required build the wall blocks exactly when the client says the account
     * has no consent in effect (so nothing is linked, uploaded or recorded), and in an optional build the prompt asks
     * exactly when the client's own wall would. The one row where iOS's table asks and the client's does not is a grant
     * the server refused because its text version was retired; there nothing is recorded either way.
     */
    @Test
    public void wallAgreesWithTheClient() {
        final Map<String, ConsentSnapshot> states = new LinkedHashMap<>();
        states.put("none", none());
        states.put("granted", granted());
        states.put("acked", acked());
        states.put("withdrawn", withdrawn(granted()));
        states.put("withdrawn acked", withdrawnAcked());
        states.put("withdrawn elsewhere", withdrawnRemote());
        states.put("reconsent asked", reconsentAsked());
        states.put("operator changed", operatorChangedStored());
        states.put("loss after grant", lossAfterGrant());
        states.put("link ended", linkEndedStored());
        states.put("stale", stale());
        states.put("relinked after loss", ConsentMachine.bindingLoss(linkEndedStored(), 0).snapshot);
        states.put("loss before grant", ConsentMachine.bindingLoss(granted(), T0 - 5).snapshot);
        states.put("retired version", granted().withMinValidVersion(2));
        states.put("declined", declinedOptional());
        states.put("refused", rejected("consent_text_mismatch"));
        states.put("granted again", ConsentMachine.grant(withdrawn(granted()), TEXT, ConsentMachine.Mode.REQUIRED, id(3), T0 + 300, "13.2.1", AUTH).snapshot);
        for (Map.Entry<String, ConsentSnapshot> entry : states.entrySet()) {
            final ConsentSnapshot snapshot = entry.getValue();
            final int current = Math.max(1, snapshot.minValidVersion);
            assertEquals(entry.getKey() + " (required)", snapshot.wall(ConsentMachine.Mode.REQUIRED) == ConsentMachine.Wall.BLOCKING,
                decide(true, snapshot, current, false).blocks());
            assertEquals(entry.getKey() + " (optional)", snapshot.wall(ConsentMachine.Mode.OPTIONAL) == ConsentMachine.Wall.PROMPT,
                decide(false, snapshot, current, false).presentation == Presentation.PROMPT);
            // No wall in a required build means the client's state is a grant in effect.
            if (!decide(true, snapshot, current, false).blocks()) {
                assertEquals(entry.getKey(), ConsentMachine.State.GRANTED, snapshot.effectiveState());
            }
        }
        final ConsentSnapshot retired = rejected(ZgConsentWallPolicy.VERSION_RETIRED_CODE);
        assertTrue(decide(true, retired, 1, false).blocks());
        assertEquals(ConsentMachine.Wall.NONE, retired.wall(ConsentMachine.Mode.REQUIRED));
        assertFalse("nothing is recorded under a refused grant", retired.recordingAllowed());
    }

    @Test
    public void settingsRowValue() {
        assertEquals(Summary.NONE, ZgConsentFacts.summary(null));
        assertEquals(Summary.NONE, ZgConsentFacts.summary(none()));
        assertEquals(Summary.GRANTED, ZgConsentFacts.summary(granted()));
        assertEquals(Summary.GRANTED, ZgConsentFacts.summary(acked()));
        assertEquals(Summary.DECLINED, ZgConsentFacts.summary(declinedOptional()));
        assertEquals(Summary.WITHDRAWN, ZgConsentFacts.summary(withdrawn(granted())));
        assertEquals(Summary.WITHDRAWN, ZgConsentFacts.summary(withdrawnRemote()));
        assertEquals(Summary.REVIEW, ZgConsentFacts.summary(reconsentAsked()));
        assertEquals(Summary.REVIEW, ZgConsentFacts.summary(lossAfterGrant()));
        assertEquals(Summary.REVIEW, ZgConsentFacts.summary(linkEndedStored()));
        assertEquals(Summary.REVIEW, ZgConsentFacts.summary(stale()));
        assertEquals(Summary.REVIEW, ZgConsentFacts.summary(granted().withMinValidVersion(2)));
        assertEquals(Summary.REVIEW, ZgConsentFacts.summary(rejected("consent_text_mismatch")));
    }

    @Test
    public void dataAndConsentStatus() {
        assertEquals(Status.NONE, ZgConsentFacts.status(null, AUTH, false, false, false));
        assertEquals(Status.NONE, ZgConsentFacts.status(none(), AUTH, false, false, false));
        // A grant is "not sent yet" until the server acknowledged it at this origin.
        assertEquals(Status.PENDING, ZgConsentFacts.status(granted(), AUTH, false, false, false));
        assertEquals(Status.PENDING, ZgConsentFacts.status(granted(), AUTH, true, false, false));
        assertEquals(Status.GRANTED, ZgConsentFacts.status(acked(), AUTH, true, false, false));
        assertEquals(Status.PENDING, ZgConsentFacts.status(acked(), "other.example", true, false, false));
        assertEquals(Status.PENDING, ZgConsentFacts.status(acked(), null, true, false, false));
        assertEquals(Status.DECLINED, ZgConsentFacts.status(declinedOptional(), AUTH, false, false, false));
        assertEquals(Status.REJECTED, ZgConsentFacts.status(rejected("consent_text_mismatch"), AUTH, true, false, false));
        assertEquals(Status.RECONSENT, ZgConsentFacts.status(reconsentAsked(), AUTH, true, false, false));
        assertEquals(Status.RECONSENT, ZgConsentFacts.status(granted().withMinValidVersion(2), AUTH, true, false, false));
        assertEquals(Status.LINK_ENDED, ZgConsentFacts.status(lossAfterGrant(), AUTH, false, false, false));
        assertEquals(Status.LINK_ENDED, ZgConsentFacts.status(stale(), AUTH, false, false, false));
        assertEquals(Status.WITHDRAWN, ZgConsentFacts.status(withdrawnRemote(), AUTH, true, false, false));
        // A deletion under way comes before everything else.
        assertEquals(Status.DELETION_PENDING, ZgConsentFacts.status(acked(), AUTH, true, false, true));
        assertEquals(Status.DELETION_PENDING, ZgConsentFacts.status(null, AUTH, false, false, true));
    }

    @Test
    public void withdrawalDelivery() {
        final ConsentSnapshot pending = withdrawn(acked());
        assertEquals(Delivery.NOT_LINKED, ZgConsentFacts.delivery(pending, AUTH, false, false));
        assertEquals(Delivery.NOT_LINKED, ZgConsentFacts.delivery(pending, AUTH, false, true));
        assertEquals(Delivery.SENDING, ZgConsentFacts.delivery(pending, AUTH, true, true));
        assertEquals(Delivery.PENDING, ZgConsentFacts.delivery(pending, AUTH, true, false));
        assertEquals(Delivery.DELIVERED, ZgConsentFacts.delivery(withdrawnAcked(), AUTH, true, true));
        assertEquals(Delivery.DELIVERED, ZgConsentFacts.delivery(withdrawnAcked(), AUTH, false, false));
        // Only a withdrawal made here is delivered from here.
        assertEquals(Delivery.DELIVERED, ZgConsentFacts.delivery(withdrawnRemote(), AUTH, true, true));
        assertEquals(Delivery.DELIVERED, ZgConsentFacts.delivery(granted(), AUTH, true, true));
        assertEquals(Delivery.DELIVERED, ZgConsentFacts.delivery(null, AUTH, true, true));

        assertEquals(Status.WITHDRAW_NOT_LINKED, ZgConsentFacts.status(pending, AUTH, false, false, false));
        assertEquals(Status.SENDING, ZgConsentFacts.status(pending, AUTH, true, true, false));
        assertEquals(Status.WITHDRAW_PENDING, ZgConsentFacts.status(pending, AUTH, true, false, false));
        assertEquals(Status.WITHDRAWN, ZgConsentFacts.status(withdrawnAcked(), AUTH, true, false, false));
    }

    @Test
    public void whichDecisionsAreOffered() {
        assertTrue(ZgConsentFacts.offersGrant(none(), false));
        assertFalse(ZgConsentFacts.offersGrant(granted(), false));
        assertTrue(ZgConsentFacts.offersGrant(rejected("consent_text_mismatch"), false));
        assertTrue(ZgConsentFacts.offersGrant(withdrawn(granted()), false));
        assertTrue(ZgConsentFacts.offersGrant(linkEndedStored(), false));
        assertFalse("not while the account is being deleted", ZgConsentFacts.offersGrant(none(), true));
        assertFalse(ZgConsentFacts.offersGrant(null, false));

        assertTrue(ZgConsentFacts.offersWithdraw(granted(), false));
        assertTrue(ZgConsentFacts.offersWithdraw(acked(), false));
        assertFalse(ZgConsentFacts.offersWithdraw(none(), false));
        assertFalse(ZgConsentFacts.offersWithdraw(withdrawn(granted()), false));
        // Needs re-consent: only while the server may still hold the earlier grant.
        assertFalse(ZgConsentFacts.offersWithdraw(reconsentAsked(), false));
        assertTrue(ZgConsentFacts.offersWithdraw(ConsentMachine.consentRequired(acked(), "reconsent_required", 0, T0 + 200).snapshot, false));
        assertFalse(ZgConsentFacts.offersWithdraw(acked(), true));
        assertFalse(ZgConsentFacts.offersWithdraw(null, false));
    }

    @Test
    public void recordViewForOtherPackages() {
        assertEquals(ZgConsentStore.State.NONE, ZgConsentStore.Record.of(null).state);
        assertEquals(ZgConsentStore.State.NONE, ZgConsentStore.Record.of(none()).state);
        final ZgConsentStore.Record granted = ZgConsentStore.Record.of(granted());
        assertEquals(ZgConsentStore.State.GRANTED, granted.state);
        assertEquals(id(1), granted.consentId);
        assertEquals(1, granted.textVersion);
        assertEquals(HASH, granted.textSha256);
        assertEquals("uz", granted.locale);
        assertEquals("required", granted.mode);
        assertEquals("2026-05-28T20:26:40Z", granted.decidedAt);
        // The effective state, as the client's gates see it.
        assertEquals(ZgConsentStore.State.NEEDS_RECONSENT, ZgConsentStore.Record.of(lossAfterGrant()).state);
        assertEquals(ZgConsentStore.State.WITHDRAWN, ZgConsentStore.Record.of(withdrawn(granted())).state);
        assertEquals(ZgConsentStore.State.WITHDRAWN_REMOTE, ZgConsentStore.Record.of(withdrawnRemote()).state);
        assertEquals(ZgConsentStore.State.DECLINED, ZgConsentStore.Record.of(declinedOptional()).state);
        assertNull(ZgConsentStore.Record.of(declinedOptional()).consentId);
    }
}
