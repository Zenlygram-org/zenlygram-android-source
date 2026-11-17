package org.telegram.messenger.zenlygram.customer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-JVM checks of the upload order and readiness (contract §6.4; design 50 §10): rule 0 (deletion pending) over every
 * other fact; rule 1 (a pending withdrawal: linked → upload first; unlinked + withdrawn → NONE; granted again → AUTO_LINK
 * with no grant for bind/start); rules 2–8; the acked set reset on a new binding re-uploads the grant; CONFIRM until a 2xx
 * of this binding in this process; {@code tasksMayRun} only then; the consents batches (a grant alone, ≤ 10
 * withdrawals, the duplicate re-send, the logout final send) and that final send ahead of every bind/revoke, at a logout
 * and at a user Unlink; the {@code binding.v1} record with {@code stale_at}, and a server 401's loss never recorded
 * before the latest grant's decided_at (ConsentMachine.lossInstant, wired into ZenlyCustomer.lost); the grant a loss
 * ended stored as needs_reconsent, so a later link never makes it effective again or runs the pipeline under it, also
 * when a commit fails (compliance X-7(b); ConsentMachine.feedBindingLoss, which ZenlyConsent.setBindingLoss runs); the
 * installation-wide admission for 1..8 accounts (budget never above 80 % of a route's limit, the selected account
 * first, other accounts' syncs ≥ 60 s apart); across a process death, the launch's second run of every event purge the
 * stored state asks for (judged against ConsentMachine's own purge steps) and of a withdrawal's cloud Tasks discard and
 * profile receipt removal (contract §6.7); and the send-time rules: CustomerApi asks the caller's rule on the worker
 * before it signs a profile sync or an events batch, and a profile sync counts only while the grant it went out under is
 * still the effective one (contract §6.4). ZenlyCustomer and ZenlyEventUploader are Android glue, so their wiring is read
 * from the source. Run with run-tests.sh, which passes the Android repository root.
 */
public final class CustomerPipelineTests {
    private static int checks;
    private static int ids;
    private static final long TG = 555000111L;
    private static final String AUTH = "zenlygram.org";
    private static final String OTHER = "10.0.2.2";
    private static final String SHA = "229fd61b5ac4c2711e1b9635b6e5b24ab44ab5564baac8a2c1067a5b127f64d9";
    private static final ConsentText.Shown TEXT = new ConsentText.Shown(1, ConsentManifest.SCOPES, "uz", "# Rozilik", SHA);
    private static final long T0 = 1793268000L; // 2026-10-29T10:00:00Z
    private static final String ZENLY_CUSTOMER = "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/customer/ZenlyCustomer.java";
    private static final String ZENLY_CONSENT = "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/customer/ZenlyConsent.java";
    private static final String ZENLY_EVENT_UPLOADER = "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/customer/ZenlyEventUploader.java";
    private static final String CUSTOMER_API = "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro/CustomerApi.java";
    private static final String ZENLY_PRO = "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro/ZenlyPro.java";
    private static final String PRO_SCREEN = "TMessagesProj/src/main/java/org/telegram/zenlygram/pro/ZgLinkAccountActivity.java";
    private static final String ZG_BACKEND = "TMessagesProj/src/main/java/org/telegram/zenlygram/cloud/ZgBackend.java";
    private static final String MESSAGES_CONTROLLER = "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java";

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("usage: CustomerPipelineTests <android repository root>");
        ruleZero();
        ruleOne();
        ruleTwo();
        ruleThreeAndFour();
        newBindingReuploads();
        ruleFiveToEight();
        readiness();
        batches();
        finalSend();
        bindingRecord();
        bindingLossFeedsConsent();
        lossNeverBeforeTheGrant(Paths.get(args[0]).resolve(ZENLY_CUSTOMER));
        aLaterLinkNeverRevivesTheEndedGrant(Paths.get(args[0]).resolve(ZENLY_CONSENT));
        admission();
        launchAfterProcessDeath(Paths.get(args[0]).resolve(ZENLY_CUSTOMER));
        sendTimeRules(Paths.get(args[0]));
        finalSendBeforeEveryRevoke(Paths.get(args[0]));
        System.out.println("Customer pipeline: " + checks + " checks passed");
    }

    // ---- Rules ----

    private static void ruleZero() {
        for (int mask = 0; mask < 1 << 9; mask++) {
            CustomerPipeline.Facts f = ready();
            f.deletionPending = true;
            f.linked = bit(mask, 0);
            f.pendingWithdraw = bit(mask, 1);
            f.uploadable = bit(mask, 2);
            f.grantAcked = bit(mask, 3);
            f.profileSettled = bit(mask, 4);
            f.profileDue = bit(mask, 5);
            f.flushDue = bit(mask, 6);
            f.confirmed = bit(mask, 7);
            f.resendGrant = bit(mask, 8);
            for (ConsentMachine.State state : ConsentMachine.State.values()) {
                f.state = state;
                check(CustomerPipeline.next(f) == CustomerPipeline.Step.NONE, "rule 0: a pending deletion sends nothing (" + mask + "," + state + ")");
                check(!CustomerPipeline.tasksMayRun(f), "rule 0: no Tasks while a deletion is pending");
            }
        }
    }

    private static void ruleOne() {
        // A withdrawal made while linked goes first, whatever the state now.
        for (ConsentMachine.State state : ConsentMachine.State.values()) {
            CustomerPipeline.Facts f = ready();
            f.state = state;
            f.pendingWithdraw = true;
            check(CustomerPipeline.next(f) == CustomerPipeline.Step.UPLOAD_CONSENTS, "rule 1: pending withdraw + linked → upload (" + state + ")");
            f.profileStopped = true;
            check(CustomerPipeline.next(f) == CustomerPipeline.Step.NONE, "rule 1: a stopped binding sends nothing (" + state + ")");
        }
        // Real snapshots: grant, acked, unlinked withdraw (pending) → NONE; granted again → AUTO_LINK without a link consent.
        ConsentSnapshot granted = acked(granted(T0), AUTH);
        ConsentSnapshot withdrawn = withdraw(granted, T0 + 100);
        CustomerPipeline.Facts f = CustomerPipeline.Facts.of(withdrawn, AUTH);
        f.linked = false;
        check(f.pendingWithdraw && f.state == ConsentMachine.State.WITHDRAWN, "SETUP: an un-acked withdrawal");
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.NONE, "rule 1: pending withdraw + unlinked + withdrawn → NONE (no link only for it)");
        f.linked = true;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.UPLOAD_CONSENTS, "rule 1: once linked, the withdrawal is uploaded");
        check(CustomerPipeline.consentsBatch(withdrawn, AUTH, false).get(0).isWithdraw(), "the withdrawal is the batch");

        ConsentSnapshot again = ConsentMachine.grant(withdrawn, TEXT, ConsentMachine.Mode.REQUIRED, id(++ids), T0 + 200, "1.0.0", AUTH).snapshot;
        f = CustomerPipeline.Facts.of(again, AUTH);
        f.linked = false;
        check(f.pendingWithdraw && f.state == ConsentMachine.State.GRANTED, "SETUP: granted again with the withdrawal still pending");
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.AUTO_LINK, "rule 1: granted again → AUTO_LINK");
        check(again.grantJsonIfGranted(AUTH) == null, "... and bind/start carries no consent (linkGrantJson null) while a withdraw is pending");
        f.linked = true;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.UPLOAD_CONSENTS, "after the link: upload");
        List<ConsentEvent> first = CustomerPipeline.consentsBatch(again, AUTH, false);
        check(first.size() == 1 && first.get(0).isWithdraw(), "oldest first: the withdrawal before the newer grant");
        ConsentSnapshot afterWithdraw = ConsentMachine.consentsAnswered(again, AUTH, results(first.get(0).consentId, "recorded"), null, T0 + 300).snapshot;
        List<ConsentEvent> second = CustomerPipeline.consentsBatch(afterWithdraw, AUTH, false);
        check(second.size() == 1 && second.get(0).isGrant() && second.get(0).consentId.equals(again.latestGrant().consentId), "then the new grant alone");
    }

    private static void ruleTwo() {
        for (ConsentMachine.State state : ConsentMachine.State.values()) {
            if (state == ConsentMachine.State.GRANTED) continue;
            CustomerPipeline.Facts f = ready();
            f.state = state;
            check(CustomerPipeline.next(f) == CustomerPipeline.Step.NONE, "rule 2: " + state + " sends nothing");
            check(!CustomerPipeline.tasksMayRun(f), "rule 2: no Tasks for " + state);
            f.linked = false;
            check(CustomerPipeline.next(f) == CustomerPipeline.Step.NONE, "rule 2: and never links for " + state);
        }
        CustomerPipeline.Facts f = ready();
        f.latestRejected = true;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.NONE && !CustomerPipeline.tasksMayRun(f), "rule 2: a rejected latest grant stops it");
        f.linked = false;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.NONE, "rule 2: and never links");
        // Binding loss and stale grant: the derived needs_reconsent.
        ConsentSnapshot lost = ConsentMachine.bindingLoss(acked(granted(T0), AUTH), T0 + 10).snapshot;
        check(CustomerPipeline.Facts.of(lost, AUTH).state == ConsentMachine.State.NEEDS_RECONSENT, "a loss after the grant → needs_reconsent");
        CustomerPipeline.Facts facts = CustomerPipeline.Facts.of(lost, AUTH);
        facts.linked = false;
        check(CustomerPipeline.next(facts) == CustomerPipeline.Step.NONE, "rule 2: no auto-link after a binding loss");
    }

    private static void ruleThreeAndFour() {
        CustomerPipeline.Facts f = ready();
        f.linked = false;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.AUTO_LINK, "rule 3: not linked → AUTO_LINK");
        check(!CustomerPipeline.tasksMayRun(f), "rule 3: no Tasks unlinked");
        f = ready();
        f.uploadable = true;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.UPLOAD_CONSENTS, "rule 4: pending entries → upload");
        f = ready();
        f.resendGrant = true;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.UPLOAD_CONSENTS, "rule 4: the reconcile's re-send → upload");
        f = ready();
        f.grantAcked = false;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.NONE, "rule 4: an un-acked grant that cannot be sent → NONE");
        check(!CustomerPipeline.tasksMayRun(f), "rule 4: no Tasks without an acked grant");

        ConsentSnapshot fresh = granted(T0);
        CustomerPipeline.Facts real = CustomerPipeline.Facts.of(fresh, AUTH);
        real.linked = true;
        check(real.uploadable && !real.grantAcked, "SETUP: a new grant waits for upload");
        check(CustomerPipeline.next(real) == CustomerPipeline.Step.UPLOAD_CONSENTS, "rule 4 on a real snapshot");
        real = CustomerPipeline.Facts.of(acked(fresh, AUTH), AUTH);
        check(!real.uploadable && real.grantAcked, "acked at this authority");
        real = CustomerPipeline.Facts.of(acked(fresh, AUTH), OTHER);
        check(real.uploadable && !real.grantAcked, "acked elsewhere only: uploaded again at this origin");
        real = CustomerPipeline.Facts.of(fresh, null);
        check(!real.grantAcked, "no configured origin: nothing is acked");
    }

    private static void newBindingReuploads() {
        ConsentSnapshot acked = acked(granted(T0), AUTH);
        CustomerPipeline.Binding stored = CustomerPipeline.Binding.NONE.adopted("01JBINDING00000000000000AA");
        check(!stored.isNewBinding("01JBINDING00000000000000AA") && stored.isNewBinding("01JBINDING00000000000000BB")
                && CustomerPipeline.Binding.NONE.isNewBinding("01JBINDING00000000000000AA"), "a new binding id is another binding");
        ConsentSnapshot cleared = ConsentMachine.clearAcked(acked, AUTH).snapshot;
        CustomerPipeline.Facts f = CustomerPipeline.Facts.of(cleared, AUTH);
        f.linked = true;
        f.profileSettled = true;
        f.confirmed = true;
        check(f.uploadable && !f.grantAcked, "the acked set of the authority is cleared");
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.UPLOAD_CONSENTS, "every new binding re-uploads its grant");
        check(!CustomerPipeline.tasksMayRun(f), "and Tasks wait for it");
        check(CustomerPipeline.Facts.of(ConsentMachine.clearAcked(acked, OTHER).snapshot, AUTH).grantAcked, "another authority keeps its acks");
    }

    private static void ruleFiveToEight() {
        CustomerPipeline.Facts f = ready();
        f.profileStopped = true;
        f.uploadable = true;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.NONE, "rule 5: a stopped binding sends nothing, not even consents");
        f = ready();
        f.profileSettled = false;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.PROFILE_SYNC, "rule 5: not settled → sync");
        f.flushDue = true;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.PROFILE_SYNC, "rule 5: events wait for the first sync");
        f = ready();
        f.profileDue = true;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.PROFILE_SYNC, "rule 5: due → sync");
        f = ready();
        f.flushDue = true;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.EVENTS_FLUSH, "rule 6: a due flush");
        f.confirmed = false;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.EVENTS_FLUSH, "rule 6 before rule 7");
        f.flushDue = false;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.CONFIRM, "rule 7: no 2xx in this process yet → CONFIRM");
        f.confirmed = true;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.READY, "rule 8: READY");
    }

    private static void readiness() {
        CustomerPipeline.Facts f = ready();
        check(CustomerPipeline.tasksMayRun(f), "every condition holds → Tasks may run");
        String[] names = {"granted", "linked", "grantAcked", "profileSettled", "!profileStopped", "!deletionPending", "confirmed", "!rejected",
                "!pendingWithdraw"};
        for (int i = 0; i < names.length; i++) {
            f = ready();
            switch (i) {
                case 0: f.state = ConsentMachine.State.NEEDS_RECONSENT; break;
                case 1: f.linked = false; break;
                case 2: f.grantAcked = false; break;
                case 3: f.profileSettled = false; break;
                case 4: f.profileStopped = true; break;
                case 5: f.deletionPending = true; break;
                case 6: f.confirmed = false; break;
                case 7: f.latestRejected = true; break;
                default: f.pendingWithdraw = true; break;
            }
            check(!CustomerPipeline.tasksMayRun(f), "Tasks need " + names[i]);
        }
        f = ready();
        f.profileDue = true;
        f.flushDue = true;
        check(CustomerPipeline.tasksMayRun(f), "a due refresh or flush does not take readiness away once confirmed");
    }

    // ---- Batches ----

    private static void batches() {
        ConsentSnapshot granted = granted(T0);
        List<ConsentEvent> batch = CustomerPipeline.consentsBatch(granted, AUTH, false);
        check(batch.size() == 1 && batch.get(0).isGrant(), "a grant alone");
        check(ConsentEvent.requestBody(batch).startsWith("{\"schema_version\":1,\"consents\":[{\"consent_id\":"), "its request body");
        ConsentSnapshot acked = acked(granted, AUTH);
        check(CustomerPipeline.consentsBatch(acked, AUTH, false).isEmpty(), "nothing pending → nothing");
        List<ConsentEvent> resend = CustomerPipeline.consentsBatch(acked, AUTH, true);
        check(resend.size() == 1 && resend.get(0).consentId.equals(acked.latestGrant().consentId), "the reconcile / CONFIRM re-sends the acked grant");
        check(CustomerPipeline.consentsBatch(reject(granted), AUTH, true).isEmpty(), "never a rejected grant");

        // Twelve withdrawals waiting (each followed by a grant that was acked): ten go in the first request, oldest first.
        ConsentSnapshot s = acked;
        List<String> withdrawals = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            s = withdraw(s, T0 + 10 + i * 10L);
            withdrawals.add(s.latestWithdrawEvent().consentId);
            s = ConsentMachine.grant(s, TEXT, ConsentMachine.Mode.REQUIRED, id(++ids), T0 + 15 + i * 10L, "1.0.0", AUTH).snapshot;
            s = ackOnly(s, AUTH, s.latestGrant().consentId);
        }
        List<ConsentEvent> first = CustomerPipeline.consentsBatch(s, AUTH, false);
        check(first.size() == ConsentEvent.MAX_WITHDRAWALS_PER_REQUEST, "at most ten per request: " + first.size());
        for (ConsentEvent event : first) check(event.isWithdraw(), "a withdrawal batch holds only withdrawals");
        check(CustomerPipeline.ids(first).equals(withdrawals.subList(0, 10)), "oldest first");
        ConsentEvent.requestBody(first); // a valid request (ascending recorded_at)
        s = ConsentMachine.consentsAnswered(s, AUTH, resultsOf(first), null, T0 + 500).snapshot;
        check(CustomerPipeline.ids(CustomerPipeline.consentsBatch(s, AUTH, false)).equals(withdrawals.subList(10, 12)), "then the other two");
    }

    private static void finalSend() {
        ConsentSnapshot s = acked(granted(T0), AUTH);
        check(CustomerPipeline.finalBatches(s, AUTH, 2).isEmpty(), "no pending withdrawal → no final send");
        // 25 withdrawals in a row: decided withdraw → grant → withdraw … with only the grants acked.
        List<String> withdrawals = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            s = withdraw(s, T0 + 10 + i * 10L);
            withdrawals.add(s.latestWithdrawEvent().consentId);
            s = ConsentMachine.grant(s, TEXT, ConsentMachine.Mode.REQUIRED, id(++ids), T0 + 15 + i * 10L, "1.0.0", AUTH).snapshot;
            s = ackOnly(s, AUTH, s.latestGrant().consentId);
        }
        List<List<ConsentEvent>> batches = CustomerPipeline.finalBatches(s, AUTH, 2);
        check(batches.size() == 2 && batches.get(0).size() == 10 && batches.get(1).size() == 10, "≤ 2 bodies of ≤ 10: " + batches.size());
        List<String> sent = new ArrayList<>();
        for (List<ConsentEvent> batch : batches) {
            ConsentEvent.requestBody(batch); // throws for an invalid batch (ascending recorded_at, withdrawals only)
            sent.addAll(CustomerPipeline.ids(batch));
        }
        check(sent.equals(withdrawals.subList(5, 25)), "the newest 20 pending withdrawals, oldest first");
        check(CustomerPipeline.finalBatches(s, AUTH, 1).size() == 1 && CustomerPipeline.finalBatches(s, AUTH, 1).get(0).get(9).consentId
                .equals(withdrawals.get(24)), "one body: the newest ten");
        check(CustomerPipeline.finalBatches(s, OTHER, 2).size() == 2, "per authority");
    }

    // ---- binding.v1 ----

    private static void bindingRecord() {
        CustomerPipeline.Binding none = CustomerPipeline.Binding.NONE;
        check(none.isEmpty() && none.consentLoss() == 0, "none");
        check("{\"v\":1,\"binding_id\":\"\",\"lost_at\":0,\"lost_code\":\"\",\"stale_at\":0}".equals(none.json()), "the documented JSON: " + none.json());
        CustomerPipeline.Binding adopted = none.adopted("01JBINDING00000000000000AA");
        CustomerPipeline.Binding lost = adopted.lost(T0 + 50, "device_revoked");
        check(lost.lostAt == T0 + 50 && "device_revoked".equals(lost.lostCode) && lost.bindingId.equals(adopted.bindingId), "a loss keeps the id");
        check(lost.lost(T0 + 10, "invalid_token").lostAt == T0 + 50, "a loss never moves back");
        check("session_invalid".equals(adopted.lost(T0, "Not A Code").lostCode), "an unstable code is not stored");
        CustomerPipeline.Binding stale = lost.stale(T0 + 20);
        check(stale.staleAt == T0 + 20 && stale.consentLoss() == T0 + 50 && stale.stale(T0 + 5).staleAt == T0 + 20, "stale_at only grows");
        CustomerPipeline.Binding relinked = stale.adopted("01JBINDING00000000000000BB");
        check(relinked.lostAt == 0 && relinked.lostCode.isEmpty() && relinked.staleAt == T0 + 20 && relinked.consentLoss() == T0 + 20,
                "a new link ends the loss, never the stale grant");
        for (CustomerPipeline.Binding record : Arrays.asList(none, adopted, lost, stale, relinked)) {
            check(record.equals(CustomerPipeline.Binding.fromTree(MiniJson.parse(record.json()))), "round trip " + record.json());
        }
        check(CustomerPipeline.Binding.fromTree(MiniJson.parse("{\"v\":1,\"binding_id\":\"x\",\"lost_at\":7,\"lost_code\":\"invalid_token\"}"))
                .staleAt == 0, "a record without stale_at reads 0");
        for (String bad : new String[]{"{}", "[]", "{\"v\":2,\"binding_id\":\"\",\"lost_at\":0,\"lost_code\":\"\",\"stale_at\":0}",
                "{\"v\":1,\"binding_id\":3,\"lost_at\":0,\"lost_code\":\"\",\"stale_at\":0}",
                "{\"v\":1,\"binding_id\":\"\",\"lost_at\":-1,\"lost_code\":\"\",\"stale_at\":0}",
                "{\"v\":1,\"binding_id\":\"\",\"lost_at\":0,\"lost_code\":null,\"stale_at\":0}"}) {
            check(CustomerPipeline.Binding.fromTree(MiniJson.parse(bad)) == null, "not a record: " + bad);
        }
        CustomerPipeline.Binding unreadable = CustomerPipeline.Binding.unreadable(T0);
        check(unreadable.lostAt == T0 && unreadable.consentLoss() == T0, "an unreadable record fails closed as a loss now");
    }

    /** The loss instant fed to the consent snapshot: lost_at or the refused grant's own recorded_at (never "now"). */
    private static void bindingLossFeedsConsent() {
        ConsentSnapshot old = acked(granted(T0), AUTH);
        long oldAt = old.latestGrant().decidedAt;
        CustomerPipeline.Binding stale = CustomerPipeline.Binding.NONE.adopted("b").stale(oldAt);
        ConsentMachine.Step step = ConsentMachine.bindingLoss(old, stale.consentLoss());
        check(step.snapshot.effectiveState() == ConsentMachine.State.NEEDS_RECONSENT && step.purgeEvents, "the refused grant is needs_reconsent");
        check(fed(old, stale.consentLoss()).record.state == ConsentMachine.State.NEEDS_RECONSENT,
                "... and stored so by the feed, also when the 409's own transition did not match the grant");
        // A newer grant made before the 409 arrived stays usable: stale_at is the refused grant's time, not the answer's.
        ConsentSnapshot newer = ConsentMachine.grant(withdraw(old, T0 + 5), TEXT, ConsentMachine.Mode.REQUIRED, id(++ids), T0 + 6, "1.0.0", AUTH).snapshot;
        ConsentMachine.Step kept = ConsentMachine.bindingLoss(newer, stale.consentLoss());
        check(kept.snapshot.effectiveState() == ConsentMachine.State.GRANTED && fed(newer, stale.consentLoss()).record.state == ConsentMachine.State.GRANTED,
                "a grant newer than the refused one is unaffected");
        CustomerPipeline.Binding lost = CustomerPipeline.Binding.NONE.adopted("b").lost(T0 + 100, "session_revoked");
        ConsentSnapshot ended = fed(newer, lost.consentLoss());
        check(ended.effectiveState() == ConsentMachine.State.NEEDS_RECONSENT && ended.record.state == ConsentMachine.State.NEEDS_RECONSENT,
                "a loss after the grant → needs_reconsent, stored");
        check(fed(ended, lost.adopted("c").consentLoss()).effectiveState() == ConsentMachine.State.NEEDS_RECONSENT
                        && fed(newer, lost.adopted("c").consentLoss()).effectiveState() == ConsentMachine.State.GRANTED,
                "a new link (lost_at cleared) does not lift a grant the loss ended (compliance X-7(b)); a grant no loss ended stays");
    }

    /**
     * Compliance design 60 §8 X-7(b) (its contract issues C-13 and C-21, review M3; iOS design 40 §8.1): once the server
     * ended the binding a grant linked (a 401 of the drop family), that grant stays ended until a NEW grant, and its
     * consent id is never again the basis of a link. ZenlyConsent.setBindingLoss stores it as needs_reconsent (LINK_ENDED)
     * right after publishing the loss (ConsentMachine.linkEnded), so the later link that clears lost_at
     * (Binding.adopted) leaves it ended: no consent on bind/start, and no consents upload, profile sync, events or Tasks
     * run under it on the new binding. Checked on the pure machine and pipeline, through the feed itself with commits
     * that fail (failedCommitsNeverReviveTheEndedGrant), and against ZenlyConsent's source (setBindingLoss is that feed,
     * written by its update).
     */
    private static void aLaterLinkNeverRevivesTheEndedGrant(Path source) throws IOException {
        ConsentSnapshot granted = acked(granted(T0), AUTH);
        String oldGrant = granted.latestGrant().consentId;
        CustomerPipeline.Binding binding = CustomerPipeline.Binding.NONE.adopted("01JBINDING00000000000000AA");
        CustomerPipeline.Binding lost = binding.lost(ConsentMachine.lossInstant(granted, T0 + 60), "device_revoked");
        CustomerPipeline.Binding relinked = lost.adopted("01JBINDING00000000000000BB");
        check(lost.consentLoss() == T0 + 60 && relinked.consentLoss() == 0 && !relinked.isEmpty(),
                "SETUP: the 401 records lost_at, and the later link (another binding id) clears it");

        // A loss kept in memory alone (the commit of the grant it ended failed) is not lifted by that link either: the
        // cleared loss stores that grant (ConsentMachine.bindingLoss), so the new binding, whose acked set is cleared
        // first (contract §6.4 step 3), uploads nothing of it (review2; the feed with failing commits is checked below).
        ConsentSnapshot memoryOnly = ConsentMachine.bindingLoss(ConsentMachine.clearAcked(
                ConsentMachine.bindingLoss(granted, lost.consentLoss()).snapshot, AUTH).snapshot, relinked.consentLoss()).snapshot;
        CustomerPipeline.Facts kept = CustomerPipeline.Facts.of(memoryOnly, AUTH);
        kept.linked = true;
        check(memoryOnly.record.state == ConsentMachine.State.NEEDS_RECONSENT && memoryOnly.bindingLostAt == 0
                && CustomerPipeline.next(kept) == CustomerPipeline.Step.NONE && memoryOnly.grantJsonIfGranted(AUTH) == null,
                "a loss kept in memory alone: the later link's cleared loss stores the grant it ended, nothing of it runs");

        // The feed stores the ended grant; the later link clears the loss and nothing of the old grant runs.
        ConsentSnapshot ended = fed(granted, lost.consentLoss());
        check(ended.record.state == ConsentMachine.State.NEEDS_RECONSENT && ended.notice() == ConsentMachine.Notice.LINK_ENDED
                && ended.latestGrant().consentId.equals(oldGrant), "the loss stores the grant it ended as needs_reconsent (link_ended)");
        // ZenlyCustomer.adopted's order: the new binding's acked set is cleared first, then the cleared loss is fed.
        ConsentSnapshot afterLink = fed(ConsentMachine.clearAcked(ended, AUTH).snapshot, relinked.consentLoss());
        check(afterLink.bindingLostAt == 0 && afterLink.effectiveState() == ConsentMachine.State.NEEDS_RECONSENT
                && afterLink.grantJsonIfGranted(AUTH) == null && !afterLink.recordingAllowed(),
                "after the later link the old grant is still ended: no consent on bind/start, nothing recorded");
        CustomerPipeline.Facts f = CustomerPipeline.Facts.of(afterLink, AUTH);
        f.linked = true;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.NONE && !CustomerPipeline.tasksMayRun(f),
                "... and the pipeline sends nothing on the new binding: no consents upload, profile sync, events or Tasks");
        check(restart(afterLink, relinked.consentLoss()).effectiveState() == ConsentMachine.State.NEEDS_RECONSENT, "... after a restart too");

        // Only a new grant (a new consent id, decided after the loss) is the basis of the new binding's uploads.
        ConsentSnapshot regranted = ConsentMachine.grant(afterLink, TEXT, ConsentMachine.Mode.REQUIRED, id(++ids), T0, "1.0.0", AUTH).snapshot;
        CustomerPipeline.Facts g = CustomerPipeline.Facts.of(regranted, AUTH);
        g.linked = true;
        check(regranted.effectiveState() == ConsentMachine.State.GRANTED && !regranted.latestGrant().consentId.equals(oldGrant)
                && regranted.latestGrant().decidedAt > lost.lostAt && CustomerPipeline.next(g) == CustomerPipeline.Step.UPLOAD_CONSENTS,
                "a new grant lifts it; the new binding's uploads then run under that grant");
        check(restart(regranted, lost.consentLoss()).effectiveState() == ConsentMachine.State.GRANTED,
                "the old loss fed again (a launch before the link was saved) leaves the new grant effective");
        failedCommitsNeverReviveTheEndedGrant(granted, lost, relinked);

        // ZenlyConsent (Android glue, read from its source): setBindingLoss runs exactly that feed through its own update,
        // and update writes as FeedStore does: a changed step is published only after its stored values were committed.
        String code = code(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
        String feed = squeeze(body(code, "public static ConsentMachine.Step setBindingLoss(long tgUserId, final long lostAt)"));
        check(feed.equals("{ return ConsentMachine.feedBindingLoss(transition -> update(tgUserId, transition::apply), lostAt); }"),
                "ZenlyConsent.setBindingLoss is ConsentMachine.feedBindingLoss through ZenlyConsent.update: " + feed);
        check(squeeze(code).split("ConsentMachine\\.feedBindingLoss\\(|ConsentMachine\\.bindingLoss\\(|ConsentMachine::linkEnded"
                + "|ConsentMachine\\.linkEnded\\(", -1).length == 2, "... the one place the consent store applies a binding loss");
        String write = squeeze(body(code, "private static ConsentMachine.Step update(long tgUserId, Transition transition)"));
        int unchanged = write.indexOf("if (!step.changed) return step;");
        int commit = write.indexOf("if (!step.snapshot.storedValues().equals(current.storedValues()) && !commitLocked(tgUserId, step.snapshot)) return null;");
        int publish = write.indexOf("publishLocked(tgUserId, step.snapshot);");
        check(unchanged >= 0 && commit > unchanged && publish > commit && write.split("publishLocked\\(", -1).length == 2,
                "ZenlyConsent.update publishes a changed step only after the commit of changed stored values succeeded: " + write);
    }

    /**
     * Review2: the feed ZenlyConsent.setBindingLoss runs (ConsentMachine.feedBindingLoss), through a store that writes as
     * ZenlyConsent.update does (pinned above) and whose commits fail on demand. The loss ends the grant in this process
     * whatever fails; every later feed stores the ended grant again; and the feed that clears the loss at a later link
     * stores it in the same commit, or publishes nothing and keeps the old loss in memory. Only a restart after every
     * consent commit failed, with lost_at already 0 in binding.v1, finds the grant effective again (a recorded residual).
     */
    private static void failedCommitsNeverReviveTheEndedGrant(ConsentSnapshot granted, CustomerPipeline.Binding lost,
            CustomerPipeline.Binding relinked) {
        final long lostAt = lost.consentLoss();
        check(lostAt > granted.latestGrant().decidedAt && relinked.consentLoss() == 0, "SETUP: the 401's loss, then the later link's 0");

        // The 401's commit fails: the loss is published without one, so the grant ends in this process at once.
        FeedStore a = new FeedStore(granted, true);
        ConsentMachine.Step step = a.feed(lostAt);
        check(step != null && step.purgeEvents && a.commits == 1 && a.published.bindingLostAt == lostAt
                && a.published.effectiveState() == ConsentMachine.State.NEEDS_RECONSENT && a.published.grantJsonIfGranted(AUTH) == null
                && a.published.storedValues().equals(granted.storedValues()),
                "a 401 whose commit fails: the loss ends the grant in memory and reports the purge; nothing is stored");
        // Commits work again; the later link (clearAcked, then the cleared loss) stores the ended grant with it (probe P2a).
        check(a.update(current -> ConsentMachine.clearAcked(current, AUTH)) != null, "SETUP: the link's clearAcked is stored");
        int commits = a.commits;
        ConsentMachine.Step cleared = a.feed(relinked.consentLoss());
        check(cleared != null && cleared.changed && a.commits == commits + 1 && a.published.bindingLostAt == 0
                && a.published.record.state == ConsentMachine.State.NEEDS_RECONSENT && a.published.notice() == ConsentMachine.Notice.LINK_ENDED
                && a.published.record.changedAt == lostAt && a.published.grantJsonIfGranted(AUTH) == null && !a.published.recordingAllowed(),
                "the later link's cleared loss stores the ended grant in the same commit (dated at the loss)");
        CustomerPipeline.Facts linked = CustomerPipeline.Facts.of(a.published, AUTH);
        linked.linked = true;
        check(CustomerPipeline.next(linked) == CustomerPipeline.Step.NONE && !CustomerPipeline.tasksMayRun(linked)
                && restart(a.published, 0).effectiveState() == ConsentMachine.State.NEEDS_RECONSENT,
                "... nothing runs on the new binding, and a launch that feeds no loss keeps it ended");

        // Every commit fails from the 401 on (probe P2b): the cleared loss publishes nothing, the old loss stays in memory.
        FeedStore b = new FeedStore(granted, true, true, true, true, true);
        b.feed(lostAt);
        check(b.update(current -> ConsentMachine.clearAcked(current, AUTH)) == null, "SETUP: the link's clearAcked commit fails too");
        check(b.feed(relinked.consentLoss()) == null && b.published.bindingLostAt == lostAt
                && b.published.effectiveState() == ConsentMachine.State.NEEDS_RECONSENT && b.published.notice() == ConsentMachine.Notice.LINK_ENDED
                && b.published.grantJsonIfGranted(AUTH) == null && !b.published.recordingAllowed()
                && b.published.storedValues().equals(granted.storedValues()),
                "a cleared loss whose commit fails publishes nothing: the old loss stays, the grant stays ended in this process");
        CustomerPipeline.Facts failing = CustomerPipeline.Facts.of(b.published, AUTH);
        failing.linked = true;
        check(CustomerPipeline.next(failing) == CustomerPipeline.Step.NONE && !CustomerPipeline.tasksMayRun(failing),
                "... and nothing runs on the new binding");
        check(restart(b.published, relinked.consentLoss()).effectiveState() == ConsentMachine.State.GRANTED,
                "RESIDUAL (carry-forward, review2): with no consent commit stored and lost_at 0 in binding.v1, the next launch "
                        + "feeds no loss and the stored grant is effective again");

        // Every feed retries: the same loss fed again (a sign-in, a consent_stale, another 401 at that lost_at) stores the
        // grant a failed commit left, although the value did not change.
        FeedStore c = new FeedStore(granted, true);
        c.feed(lostAt);
        ConsentMachine.Step again = c.feed(lostAt);
        check(again != null && !again.changed && c.commits == 2 && c.published.record.state == ConsentMachine.State.NEEDS_RECONSENT
                && c.published.record.changedAt == lostAt && restart(c.published, 0).effectiveState() == ConsentMachine.State.NEEDS_RECONSENT,
                "the same loss fed again stores the grant the failed commit left (a launch that reads no loss keeps it ended)");

        // The cleared loss's own commit fails and the feed's second write, still under the old loss, succeeds.
        FeedStore d = new FeedStore(granted, true, true, false);
        d.feed(lostAt);
        check(d.feed(relinked.consentLoss()) == null && d.commits == 3 && d.published.bindingLostAt == lostAt
                && d.published.record.state == ConsentMachine.State.NEEDS_RECONSENT,
                "a cleared loss whose commit fails keeps the old loss, and the same feed then stores the grant it ended");
        ConsentMachine.Step next = d.feed(relinked.consentLoss());
        check(next != null && next.changed && d.published.bindingLostAt == 0 && d.published.effectiveState() == ConsentMachine.State.NEEDS_RECONSENT,
                "... the next feed clears the loss; the grant stays ended");

        // A link that keeps an older stale_at lowers the loss to it instead of clearing it: the same rule holds.
        long staleAt = granted.latestGrant().decidedAt - 30;
        CustomerPipeline.Binding staleLost = CustomerPipeline.Binding.NONE.adopted("01JBINDING00000000000000CC").stale(staleAt)
                .lost(lostAt, "session_revoked");
        CustomerPipeline.Binding staleRelinked = staleLost.adopted("01JBINDING00000000000000DD");
        FeedStore e = new FeedStore(granted, true);
        e.feed(staleLost.consentLoss());
        check(staleLost.consentLoss() == lostAt && staleRelinked.consentLoss() == staleAt && e.feed(staleRelinked.consentLoss()) != null
                && e.published.bindingLostAt == staleAt && e.published.record.state == ConsentMachine.State.NEEDS_RECONSENT
                && e.published.effectiveState() == ConsentMachine.State.NEEDS_RECONSENT,
                "a loss lowered to an older stale_at (before the grant) stores the grant the 401 ended too");
    }

    /**
     * A server 401's loss is recorded no earlier than the latest grant's decided_at (ConsentMachine.lossInstant, wired into
     * ZenlyCustomer.lost): nextInstant puts a grant made within a second of an earlier decision ahead of the clock, and a
     * loss stamped at the trusted clock alone would leave that grant effective, so it could link automatically once the
     * 401 was delivered. A grant made after the loss still lifts it.
     */
    private static void lossNeverBeforeTheGrant(Path source) throws IOException {
        // Withdrawn and granted again within one second: nextInstant places the new grant ahead of the clock.
        ConsentSnapshot regranted = ConsentMachine.grant(withdraw(acked(granted(T0), AUTH), T0), TEXT, ConsentMachine.Mode.REQUIRED,
                id(++ids), T0, "1.0.0", AUTH).snapshot;
        long decided = regranted.latestGrant().decidedAt;
        check(decided == T0 + 2 && regranted.effectiveState() == ConsentMachine.State.GRANTED,
                "SETUP: the grant is decided two seconds ahead of the clock: " + decided);
        check(ConsentMachine.bindingLoss(regranted, T0).snapshot.effectiveState() == ConsentMachine.State.GRANTED,
                "SETUP: a loss stamped at the clock alone leaves that grant effective (the defect)");
        long lostAt = ConsentMachine.lossInstant(regranted, T0);
        check(lostAt == decided, "the loss is recorded at the grant's decided_at, not before it: " + lostAt);
        CustomerPipeline.Binding lost = CustomerPipeline.Binding.NONE.adopted("b").lost(lostAt, "session_revoked");
        ConsentMachine.Step step = ConsentMachine.bindingLoss(regranted, lost.consentLoss());
        check(step.snapshot.effectiveState() == ConsentMachine.State.NEEDS_RECONSENT && step.purgeEvents
                        && step.snapshot.notice() == ConsentMachine.Notice.LINK_ENDED,
                "so the 401 ends that grant: needs_reconsent with the link-ended notice, and the events go");
        check(step.snapshot.grantJsonIfGranted(AUTH) == null && !step.snapshot.recordingAllowed(),
                "... it no longer travels with bind/start, and nothing is recorded");
        check(restart(regranted, lost.consentLoss()).effectiveState() == ConsentMachine.State.NEEDS_RECONSENT,
                "the stored loss ends it after a restart too (binding.v1 is fed at launch)");
        check(ConsentMachine.lossInstant(regranted, T0 + 100) == T0 + 100, "a clock past the grant: the clock");
        check(ConsentMachine.lossInstant(ConsentSnapshot.empty(TG, 1), T0) == T0, "no grant: the clock");
        check(ConsentMachine.lossInstant(regranted, -5) == decided && ConsentMachine.lossInstant(ConsentSnapshot.empty(TG, 1), -5) == 0,
                "never negative");
        ConsentSnapshot answered = ConsentMachine.grant(step.snapshot, TEXT, ConsentMachine.Mode.REQUIRED, id(++ids), T0, "1.0.0", AUTH).snapshot;
        check(answered.latestGrant().decidedAt > lostAt && answered.effectiveState() == ConsentMachine.State.GRANTED,
                "a grant made after the loss is effective (nextInstant puts it after the recorded loss)");

        String code = code(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
        String delivered = squeeze(body(code, "private static void lost(int account, long tg, String authority, String code, String reason)"));
        int stamped = delivered.indexOf("long lostAt = ConsentMachine.lossInstant(ZenlyConsent.snapshot(tg), now);");
        int saved = delivered.indexOf("current.binding(tg, authority, now).lost(lostAt, code);");
        int fed = delivered.indexOf("ZenlyConsent.setBindingLoss(tg, next.consentLoss());");
        check(delivered.contains("long now = ZenlyPro.trustedNowSeconds();") && stamped >= 0,
                "ZenlyCustomer.lost stamps the loss with lossInstant over the user's snapshot and the trusted clock");
        check(saved > stamped && fed > saved, "... and that instant is what binding.v1 saves and the consent snapshot gets");
        check(delivered.split("\\.lost\\(", -1).length == 2, "no other loss stamp in lost(): " + delivered);
    }

    // ---- Admission ----

    private static void admission() {
        for (CustomerOutcome.Route route : new CustomerOutcome.Route[]{CustomerOutcome.Route.CONSENTS, CustomerOutcome.Route.SYNC,
                CustomerOutcome.Route.ME}) {
            for (int accounts = 1; accounts <= 8; accounts++) simulate(route, accounts);
        }
        CustomerPipeline.Admission gate = new CustomerPipeline.Admission(new CustomerBackoff.InstallBudget());
        check(gate.admit(CustomerOutcome.Route.SYNC, false, 0), "another account's first sync");
        check(gate.waitMs(CustomerOutcome.Route.SYNC, false, 1_000) == 59_000 && !gate.admit(CustomerOutcome.Route.SYNC, false, 1_000),
                "the next other account waits 60 s");
        check(gate.admit(CustomerOutcome.Route.SYNC, true, 1_000), "the selected account is never spaced");
        check(gate.admit(CustomerOutcome.Route.SYNC, false, 60_000), "60 s later the next one");
        check(gate.admit(CustomerOutcome.Route.CONSENTS, false, 60_001), "consents are not spaced");
        try {
            new CustomerPipeline.Admission(null);
            check(false, "a budget is required");
        } catch (IllegalArgumentException expected) {
            check(true, "a budget is required");
        }
    }

    /**
     * {@code accounts} accounts (index 0 selected) each want to send {@code route} 30 times as fast as allowed, for 3 hours,
     * checked every second: never more than 80 % of the server's hourly per-device limit in any hour, the selected account
     * always gets the reserve, other accounts' syncs never closer than 60 s.
     */
    private static void simulate(CustomerOutcome.Route route, int accounts) {
        CustomerPipeline.Admission gate = new CustomerPipeline.Admission(new CustomerBackoff.InstallBudget());
        int[] wanted = new int[accounts];
        Arrays.fill(wanted, 30);
        ArrayList<Long> sends = new ArrayList<>();
        int selectedSends = 0;
        long lastOther = Long.MIN_VALUE;
        for (long now = 0; now <= 3 * 3_600_000L; now += 1_000) {
            for (int account = 0; account < accounts; account++) {
                if (wanted[account] == 0) continue;
                boolean selected = account == 0;
                long wait = gate.waitMs(route, selected, now);
                boolean admitted = gate.admit(route, selected, now);
                check(admitted == (wait == 0), route + " " + accounts + ": admit agrees with waitMs");
                if (!admitted) continue;
                wanted[account]--;
                sends.add(now);
                if (selected) {
                    selectedSends++;
                } else if (route == CustomerOutcome.Route.SYNC) {
                    check(lastOther == Long.MIN_VALUE || now - lastOther >= CustomerPipeline.Admission.OTHER_SYNC_SPACING_MS,
                            "other accounts' syncs ≥ 60 s apart");
                    lastOther = now;
                }
            }
        }
        int limit = CustomerBackoff.InstallBudget.perHour(route);
        for (int i = 0; i < sends.size(); i++) {
            int inHour = 0;
            for (int j = i; j < sends.size() && sends.get(j) < sends.get(i) + 3_600_000L; j++) inHour++;
            check(inHour <= limit, String.format(Locale.ROOT, "%s with %d accounts: %d sends in an hour ≤ %d", route, accounts, inHour, limit));
        }
        check(selectedSends >= Math.min(30, CustomerBackoff.InstallBudget.reserve(route) * 3), route + ": the selected account is served");
    }

    // ---- Launch after a process death (contract §6.7) ----

    /**
     * A withdrawal, a binding loss, a stale grant or a rejected grant commits its state at once, but the event purge it
     * asks for (ZenlyStats.forget → the events thread) and a withdrawal's cloud Tasks discard (a worker → the cloud
     * executor) run later. A process that ended in between left them undone, and the next grant and link would upload the
     * events and the unsent Tasks edits they dropped. So ZenlyCustomer.onPostInit runs them again (each is idempotent)
     * for every signed-in account whose stored state asks for them, after the stored binding loss was fed to the consent
     * snapshot and the launch pass stored needs_reconsent for the grants whose text this build renders with another hash
     * (in that order, so a grant whose link ended keeps its purge): the purge while eventsPurged(snapshot) holds, the
     * discard (and the removal of the profile receipt, which a new grant must replace before events or Tasks) while the
     * account is withdrawn. Both predicates are applied here to real snapshots as the next process decodes them, and
     * eventsPurged is judged against ConsentMachine's own purge steps.
     */
    private static void launchAfterProcessDeath(Path source) throws IOException {
        String code = code(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));

        // One predicate: withdrawn here or elsewhere.
        String predicate = squeeze(body(code, "private static boolean withdrawn(ConsentMachine.State state)"));
        check(predicate.matches("\\{ return state == ConsentMachine\\.State\\.\\w+( \\|\\| state == ConsentMachine\\.State\\.\\w+)*; \\}"),
                "withdrawn(state) is a plain membership test: " + predicate);
        Set<ConsentMachine.State> withdrawn = EnumSet.noneOf(ConsentMachine.State.class);
        for (Matcher state = Pattern.compile("ConsentMachine\\.State\\.(\\w+)").matcher(predicate); state.find(); ) {
            withdrawn.add(ConsentMachine.State.valueOf(state.group(1)));
        }
        check(withdrawn.equals(EnumSet.of(ConsentMachine.State.WITHDRAWN, ConsentMachine.State.WITHDRAWN_REMOTE)),
                "withdrawn and withdrawn_remote, nothing else: " + withdrawn);

        // The in-process transition and the launch share the predicate and the effects that run later.
        String transition = squeeze(body(code, "private static void consentTransition(int account, long tg)"));
        check(transition.contains("boolean was = withdrawn(before);") && transition.contains("boolean is = withdrawn(now);"),
                "the transition judges with the same predicate");
        String entering = block(transition, transition.indexOf("if (is && !was && before != null) {"));
        check(entering.contains("purgeEvents(account, tg);") && entering.contains("discardTasks(account, tg);"),
                "entering withdrawn purges the events and discards the unsent Tasks changes");
        check(entering.contains("forgetProfileSync(CustomerApi.forAccount(account), tg);"), "... and forgets the binding's profile receipt");
        String forget = squeeze(body(code, "private static void forgetProfileSync(CustomerApi api, long tg)"));
        check(forget.contains("if (identity.isLinked() && identity.telegramUserId == tg) api.clearProfileSync(identity);"),
                "only the user's own linked binding forgets its receipt");
        String launch = squeeze(body(code, "public static void onPostInit(final long[] signedInUserIds)"));
        String walk = "for (int account = 0; account < SLOTS; account++) { long tg = activatedUser(account); if (tg <= 0) continue;";
        int fed = launch.indexOf(walk);
        check(fed >= 0, "onPostInit walks the signed-in accounts");
        String stored = block(launch, fed);
        int fedEnd = launch.indexOf('{', fed) + stored.length();
        check(stored.contains("ZenlyConsent.setBindingLoss(tg, current.binding(tg, authority, now).consentLoss());")
                && stored.indexOf("completeErasure(account, tg, authority, false);") > stored.indexOf("ZenlyConsent.setBindingLoss("),
                "first the stored binding loss of every signed-in account is fed to the consent snapshot and a completed deletion "
                        + "finished (its consent record is gone)");
        int load = launch.indexOf("ZenlyConsent.load();");
        int normalised = launch.indexOf("ZenlyConsent.normaliseChangedTexts();");
        int loop = launch.indexOf(walk, fedEnd);
        check(load >= 0 && load < fed && normalised >= fedEnd && loop > normalised
                && normalised == launch.lastIndexOf("ZenlyConsent.normaliseChangedTexts();"),
                "then, once, the grants whose text this build renders with another hash become needs_reconsent: after the losses (a "
                        + "grant whose link ended keeps that notice and its purge), before SEEN and the purges read the state");
        check(squeeze(code).split("ZenlyConsent\\.normaliseChangedTexts\\(\\);", -1).length == 2, "... and nowhere else in ZenlyCustomer");
        String accounts = block(launch, loop);
        Matcher again = Pattern.compile("ConsentSnapshot (\\w+) = ZenlyConsent\\.snapshot\\(tg\\); ConsentMachine\\.State (\\w+) = \\1\\.effectiveState\\(\\); "
                + "SEEN\\.put\\(tg, \\2\\); if \\(eventsPurged\\(\\1\\)\\) purgeEvents\\(account, tg\\); "
                + "if \\(withdrawn\\(\\2\\)\\) \\{ discardTasks\\(account, tg\\); forgetProfileSync\\(CustomerApi\\.forAccount\\(account\\), tg\\); \\}")
                .matcher(accounts);
        check(again.find(), "onPostInit: the purge the stored state asks for runs again, and for a withdrawal the Tasks discard and the "
                + "receipt's removal");
        check(!accounts.contains("ZenlyConsent.setBindingLoss(") && !accounts.contains("completeErasure("),
                "... judged on the normalised state: no loss is fed and no deletion finished after the pass");
        String discard = squeeze(body(code, "private static void discardTasks(final int account, final long tg)"));
        check(discard.contains("new Thread(") && discard.contains("TasksCloudRepository.onConsentWithdrawn(account, tg);") && discard.contains(".start();"),
                "the Tasks discard (it waits up to 2 s) runs on a worker, never on the main thread");
        check(squeeze(body(code, "private static void purgeEvents(int account, long tg)")).contains("ZenlyStats.forget(tg);"),
                "the purge queues the delete on the events thread");

        // The stored state alone decides it: every withdrawal, uploaded or not, and nothing else.
        ConsentSnapshot granted = acked(granted(T0), AUTH);
        ConsentSnapshot local = withdraw(granted, T0 + 100);
        String withdrawal = local.latestWithdrawEvent().consentId;
        Map<String, ConsentSnapshot> purged = new LinkedHashMap<>();
        purged.put("a withdrawal not uploaded yet", local);
        purged.put("an uploaded withdrawal", ackOnly(local, AUTH, withdrawal));
        purged.put("a withdrawal superseded by a grant elsewhere", ConsentMachine.consentsAnswered(local, AUTH, results(withdrawal, "superseded"),
                new ConsentMachine.Effective("granted", id(++ids), 1, T0 + 150), T0 + 160).snapshot);
        purged.put("a withdrawal reported by the server", ConsentMachine.consentRequired(granted, "withdrawn", T0 + 200, T0 + 200).snapshot);
        check(purged.get("a withdrawal superseded by a grant elsewhere").record.activeElsewhere
                && purged.get("a withdrawal reported by the server").record.state == ConsentMachine.State.WITHDRAWN_REMOTE, "SETUP: both withdrawal answers");
        for (Map.Entry<String, ConsentSnapshot> entry : purged.entrySet()) {
            check(withdrawn.contains(restart(entry.getValue(), 0).effectiveState()), "after a restart, " + entry.getKey() + " is purged and discarded again");
        }
        ConsentSnapshot regranted = ConsentMachine.grant(local, TEXT, ConsentMachine.Mode.REQUIRED, id(++ids), T0 + 300, "1.0.0", AUTH).snapshot;
        Map<String, ConsentSnapshot> kept = new LinkedHashMap<>();
        kept.put("a grant", granted);
        kept.put("a grant made again after the withdrawal", regranted);
        kept.put("no decision", ConsentSnapshot.empty(TG, 1));
        kept.put("a decline", ConsentMachine.decline(ConsentSnapshot.empty(TG, 1), ConsentMachine.Mode.OPTIONAL, T0).snapshot);
        kept.put("a rejected grant", reject(granted(T0)));
        kept.put("a reconsent request", ConsentMachine.consentRequired(granted, "reconsent_required", 0, T0 + 400).snapshot);
        for (Map.Entry<String, ConsentSnapshot> entry : kept.entrySet()) {
            check(!withdrawn.contains(restart(entry.getValue(), 0).effectiveState()), "after a restart, " + entry.getKey() + " is not a withdrawal");
        }
        check(!withdrawn.contains(restart(granted, T0 + 50).effectiveState()), "after a restart, a binding loss is not a withdrawal");
        // The launch seeds SEEN with the withdrawn state, so a later grant in that process is a re-grant (Tasks resume).
        check(withdrawn.contains(restart(local, 0).effectiveState()) && restart(regranted, 0).recordingAllowed(),
                "a grant after the restart leaves withdrawn and records again");

        // The event purge runs again exactly where ConsentMachine's step asked for it: every way out of an effective grant,
        // restarted from what was stored (a binding loss is fed again from binding.v1, as onPostInit does).
        Predicate<ConsentSnapshot> purgesAgain = eventsPurged(code, withdrawn);
        String grantId = granted.latestGrant().consentId;
        Map<String, ConsentMachine.Step> steps = new LinkedHashMap<>();
        Map<String, Long> losses = new LinkedHashMap<>();
        steps.put("a withdrawal", ConsentMachine.withdraw(granted, ConsentMachine.Mode.REQUIRED, "uz", id(++ids), T0 + 100, "1.0.0", AUTH));
        steps.put("a newer withdrawal reported by the server", ConsentMachine.consentRequired(granted, "withdrawn", T0 + 200, T0 + 200));
        steps.put("an older withdrawal reported by the server", ConsentMachine.consentRequired(granted, "withdrawn", T0 - 200, T0 + 200));
        steps.put("a re-consent request", ConsentMachine.consentRequired(granted, "reconsent_required", 0, T0 + 300));
        steps.put("no consent on the server", ConsentMachine.consentRequired(granted, "no_consent", 0, T0 + 300));
        steps.put("an erased account's grant answered", ConsentMachine.consentsAnswered(granted, AUTH, results(grantId, "duplicate"),
                new ConsentMachine.Effective("none", null, 0, 0), T0 + 400));
        steps.put("a newer withdrawal elsewhere answered", ConsentMachine.consentsAnswered(granted, AUTH, results(grantId, "duplicate"),
                new ConsentMachine.Effective("withdrawn", id(++ids), 1, T0 + 500), T0 + 510));
        steps.put("a rejected grant", ConsentMachine.rejected(granted, Collections.singletonList(grantId), "consent_text_mismatch"));
        steps.put("a grant bind/start refused as stale", ConsentMachine.consentStale(granted, granted.latestGrantJson(), T0 + 600));
        steps.put("a binding loss after the grant", ConsentMachine.bindingLoss(granted, T0 + 700));
        losses.put("a binding loss after the grant", T0 + 700);
        steps.put("a binding loss before the grant", ConsentMachine.bindingLoss(granted, T0 - 700));
        losses.put("a binding loss before the grant", T0 - 700);
        int purging = 0;
        for (Map.Entry<String, ConsentMachine.Step> entry : steps.entrySet()) {
            ConsentMachine.Step step = entry.getValue();
            Long lost = losses.get(entry.getKey());
            check(purgesAgain.test(restart(step.snapshot, lost == null ? 0 : lost)) == step.purgeEvents,
                    "after a restart, " + entry.getKey() + (step.purgeEvents ? " is purged again" : " keeps its queue"));
            if (step.purgeEvents) purging++;
        }
        check(purging == 7 && steps.size() == 11, "SETUP: seven of the eleven steps purge (" + purging + ")");
        // Recording again (a new grant after any of them) or never having recorded: nothing to purge at launch.
        Map<String, ConsentSnapshot> recording = new LinkedHashMap<>();
        recording.put("a grant made again after a withdrawal", regranted);
        recording.put("a grant made again after a rejection", ConsentMachine.grant(steps.get("a rejected grant").snapshot, TEXT,
                ConsentMachine.Mode.REQUIRED, id(++ids), T0 + 800, "1.0.0", AUTH).snapshot);
        recording.put("a grant made again after a stale one", ConsentMachine.grant(steps.get("a grant bind/start refused as stale").snapshot, TEXT,
                ConsentMachine.Mode.REQUIRED, id(++ids), T0 + 900, "1.0.0", AUTH).snapshot);
        ConsentSnapshot lostThenGranted = ConsentMachine.grant(steps.get("a binding loss after the grant").snapshot, TEXT, ConsentMachine.Mode.REQUIRED,
                id(++ids), T0 + 1000, "1.0.0", AUTH).snapshot;
        check(lostThenGranted.recordingAllowed() && !purgesAgain.test(restart(lostThenGranted, T0 + 700)),
                "after a restart, a grant made again after a binding loss keeps its queue");
        recording.put("a retired text version (the wall asks again, the queue stays)", granted.withMinValidVersion(2));
        recording.put("no decision", ConsentSnapshot.empty(TG, 1));
        recording.put("a decline", ConsentMachine.decline(ConsentSnapshot.empty(TG, 1), ConsentMachine.Mode.OPTIONAL, T0).snapshot);
        for (Map.Entry<String, ConsentSnapshot> entry : recording.entrySet()) {
            check(!purgesAgain.test(restart(entry.getValue(), 0)), "after a restart, " + entry.getKey() + " keeps its queue");
        }

        // The launch pass (ZenlyConsent.normaliseChangedTexts: ConsentMachine.operatorChanged with this build's hash of the
        // grant's text) runs after the losses were fed: a grant whose link ended is stored with that notice, so its purge
        // runs at this and every later launch, while a changed text alone keeps the queue, as a re-consent request does.
        String rendered = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        check(!rendered.equals(granted.latestGrant().textSha256), "SETUP: this build renders the grant's text with another hash");
        ConsentSnapshot ended = ConsentMachine.operatorChanged(restart(granted, T0 + 700), rendered, T0 + 2000).snapshot;
        check(ended.record.state == ConsentMachine.State.NEEDS_RECONSENT && purgesAgain.test(ended) && purgesAgain.test(restart(ended, T0 + 700))
                && purgesAgain.test(restart(ended, 0)), "a changed text after the link ended: needs_reconsent, purged again at every launch");
        ConsentSnapshot changed = ConsentMachine.operatorChanged(restart(granted, 0), rendered, T0 + 2000).snapshot;
        check(changed.record.state == ConsentMachine.State.NEEDS_RECONSENT && !purgesAgain.test(changed) && !purgesAgain.test(restart(changed, 0)),
                "a changed text alone: needs_reconsent, the queue stays");
        check(!purgesAgain.test(fed(changed, T0 + 700)),
                "normalised before its loss was fed, a grant whose link ended would lose its purge (hence the order in onPostInit)");
    }

    /**
     * ZenlyCustomer.eventsPurged as written: one expression whose terms this test can judge (withdrawn, a notice, a
     * rejected latest grant), each applied to a snapshot as the source states it.
     */
    private static Predicate<ConsentSnapshot> eventsPurged(String code, final Set<ConsentMachine.State> withdrawn) {
        String body = squeeze(body(code, "private static boolean eventsPurged(ConsentSnapshot snapshot)"));
        Matcher returned = Pattern.compile("\\{ return (.+); \\}").matcher(body);
        check(returned.matches(), "eventsPurged(snapshot) is one expression: " + body);
        final List<Predicate<ConsentSnapshot>> terms = new ArrayList<>();
        for (String term : returned.group(1).split(" \\|\\| ")) {
            Matcher notice = Pattern.compile("snapshot\\.notice\\(\\) == ConsentMachine\\.Notice\\.(\\w+)").matcher(term);
            if (term.equals("withdrawn(snapshot.effectiveState())")) {
                terms.add(s -> withdrawn.contains(s.effectiveState()));
            } else if (notice.matches()) {
                final ConsentMachine.Notice value = ConsentMachine.Notice.valueOf(notice.group(1));
                terms.add(s -> s.notice() == value);
            } else if (term.equals("snapshot.latestRejected()")) {
                terms.add(ConsentSnapshot::latestRejected);
            } else {
                check(false, "eventsPurged: a term this test cannot judge: " + term);
            }
        }
        check(terms.size() == 3, "eventsPurged: withdrawn, the link ended and a rejected grant");
        return s -> {
            for (Predicate<ConsentSnapshot> term : terms) {
                if (term.test(s)) return true;
            }
            return false;
        };
    }

    // ---- Send-time rules (contract §6.4, §6.7) ----

    /**
     * A profile sync or an events batch can wait on the shared customer worker behind other calls (up to 30 s each), so
     * CustomerApi asks the caller's rule there, right before each attempt is signed, and refuses with NOT_ALLOWED; the
     * public calls of those two routes always carry a rule. ZenlyEventUploader hands over its send-time check (the slot
     * not forgotten by a purge, the recording rule); ZenlyCustomer ties a profile sync to the grant it went out under, on
     * the worker and again when its answer arrives, where a stale 200 records no receipt, confirms nothing and triggers no
     * flush. stillGranted is applied here to real snapshots; the behaviour runs in ZenlyCustomerSendTimeRuntimeTest.
     */
    private static void sendTimeRules(Path root) throws IOException {
        String api = code(new String(Files.readAllBytes(root.resolve(CUSTOMER_API)), StandardCharsets.UTF_8));
        String run = squeeze(body(api, "static Reply run(Boundary boundary, Object ticket, Request request)"));
        int attempt = run.indexOf("for (int attempt = 0; ; attempt++) {");
        int asked = run.indexOf("if (!allowed(request.allowed)) return local(Kind.NOT_ALLOWED, \" \").possibly(sent);");
        int signs = run.indexOf("boundary.prepare(");
        check(attempt >= 0 && asked > attempt && signs > asked && signs == run.lastIndexOf("boundary.prepare("),
                "every attempt asks the caller's rule on the worker before it is signed, and refuses as NOT_ALLOWED");
        String rule = squeeze(body(api, "private static boolean allowed(Allowed rule)"));
        check(rule.contains("if (rule == null) return true;") && rule.contains("catch (RuntimeException unknown) { return false; }"),
                "no rule allows; a rule that throws refuses");
        String flat = squeeze(api);
        for (String route : new String[]{"postProfileSync", "postEvents"}) {
            check(flat.contains("public void " + route + "(Identity expected, byte[] body, Allowed stillAllowed, Callback done) {"),
                    route + ": the public call carries a rule");
            check(!flat.contains("public void " + route + "(Identity expected, byte[] body, Callback done)"),
                    route + ": the call without a rule is not public (the exchange host tests only)");
        }
        check(flat.contains("MAX_SYNC_BYTES, stillAllowed), done);") && flat.contains("MAX_EVENTS_BYTES, stillAllowed), done);"),
                "the rule goes to the exchange");

        String uploader = code(new String(Files.readAllBytes(root.resolve(ZENLY_EVENT_UPLOADER)), StandardCharsets.UTF_8));
        String send = squeeze(body(uploader, "private static void send("));
        int post = send.indexOf("api.postEvents(identity, batch.body(), () -> !state.forgotten && allowed.allowed(), reply -> {");
        check(post >= 0, "the batch carries the uploader's send-time check to the worker: the slot not forgotten, the recording rule");
        String answer = send.substring(post);
        int notAllowed = answer.indexOf("if (reply.kind == CustomerApi.Kind.NOT_ALLOWED) {");
        String refusal = notAllowed < 0 ? "" : answer.substring(notAllowed, answer.indexOf("return;", notAllowed));
        check(notAllowed >= 0 && notAllowed < answer.indexOf("answered(") && refusal.contains("state.inFlight = false;")
                && refusal.contains("Skip.NOT_ALLOWED") && !refusal.contains("finish(") && !refusal.contains("backoff"),
                "refused on the worker: the slot is free, not allowed is reported, nothing backs off or is finished");
        check(squeeze(body(uploader, "public static void forget(int account)")).contains("if (removed != null) removed.forgotten = true;"),
                "every purge marks the slot's upload state forgotten for the worker");

        String customer = code(new String(Files.readAllBytes(root.resolve(ZENLY_CUSTOMER)), StandardCharsets.UTF_8));
        String sendSync = squeeze(body(customer, "private static long sendSync("));
        check(sendSync.contains("ConsentMachine.Grant sentUnder = ZenlyConsent.snapshot(tg).latestGrant(); "
                        + "final String grant = sentUnder == null ? null : sentUnder.consentId;")
                && sendSync.contains("api.postProfileSync(identity, body, () -> stillGranted(tg, grant), reply -> answer(account, s, epoch, "
                        + "() -> syncAnswered(account, s, api, identity, tg, grant, digest, first, reply)));"),
                "a profile sync goes out under its grant: asked on the worker and handed to its answer");
        String answered = squeeze(body(customer, "private static void syncAnswered("));
        int skip = answered.indexOf("if (reply.kind == CustomerApi.Kind.NOT_ALLOWED) return;");
        check(skip >= 0 && skip < answered.indexOf("outcomeOf("), "refused on the worker: nothing backs off or counts");
        String ok = block(answered, answered.indexOf("case OK: {"));
        int idle = ok.indexOf("s.sync = CustomerBackoff.State.IDLE;"), stale = ok.indexOf("if (!stillGranted(tg, grant)) break;");
        check(idle >= 0 && stale > idle && ok.indexOf("api.recordProfileSync(") > stale && ok.indexOf("s.confirmed = true;") > stale
                        && ok.indexOf("s.flushWanted = true;") > stale,
                "a 200 ends the backoff; only under a still-effective grant does it record the receipt, confirm and trigger a flush");
        String still = squeeze(body(customer, "private static boolean stillGranted(long tg, String grant)"));
        check(still.equals("{ ConsentSnapshot now = ZenlyConsent.snapshot(tg); return grant != null && now.recordingAllowed() "
                + "&& now.record.grant != null && grant.equals(now.record.grant.consentId) && !deletionPending(tg); }"),
                "stillGranted reads the published snapshot and the deletion gate only: " + still);

        // The same expression on real snapshots (no deletion pending).
        ConsentSnapshot granted = acked(granted(T0), AUTH);
        String grant = granted.latestGrant().consentId;
        check(stillGranted(granted, grant), "the grant it went out under counts");
        check(!stillGranted(granted, null), "no grant never counts");
        ConsentSnapshot withdrawn = withdraw(granted, T0 + 100);
        check(!stillGranted(withdrawn, grant), "withdrawn since: stale");
        check(!stillGranted(ConsentMachine.grant(withdrawn, TEXT, ConsentMachine.Mode.REQUIRED, id(++ids), T0 + 200, "1.0.0", AUTH).snapshot, grant),
                "withdrawn and granted again: another grant, stale");
        check(!stillGranted(ConsentMachine.consentRequired(granted, "withdrawn", T0 + 300, T0 + 300).snapshot, grant), "withdrawn elsewhere: stale");
        check(!stillGranted(reject(granted), grant), "rejected: stale");
        check(!stillGranted(ConsentMachine.bindingLoss(granted, T0 + 400).snapshot, grant), "the link ended: stale");
        check(!stillGranted(ConsentMachine.consentRequired(granted, "reconsent_required", 0, T0 + 500).snapshot, grant), "re-consent asked: stale");
        check(stillGranted(ConsentMachine.bindingLoss(granted, T0 - 400).snapshot, grant), "a loss before the grant leaves it effective");
    }

    /**
     * The final send of pending withdrawals runs before every bind/revoke of a linked account (design 50 §7.4): at a logout
     * (MessagesController.performLogout → ZenlyCustomer.onLogout, then ZenlyPro.unlink) and at a user Unlink
     * (ZgLinkAccountActivity.confirmUnlink → ZenlyCustomer.onUserUnlink, then pro.unlink), as iOS's logout final send runs at a
     * user Unlink too. queueFinal captures the session and queues its one task on the user lane, which is WORKER, the lane
     * unlink() queues its bind/revoke on afterwards, so the withdrawals leave first; the Unlink purges nothing. The batches
     * themselves are checked by finalSend(); this reads the glue's source.
     */
    private static void finalSendBeforeEveryRevoke(Path root) throws IOException {
        String customer = code(new String(Files.readAllBytes(root.resolve(ZENLY_CUSTOMER)), StandardCharsets.UTF_8));
        String queue = squeeze(body(customer, "private static void queueFinalWithdrawals(int account, long tg)"));
        check(queue.contains("CustomerPipeline.finalBatches(ZenlyConsent.snapshot(tg), authority, CustomerApi.MAX_FINAL_BODIES);")
                        && queue.contains("if (!identity.isLinked() || identity.telegramUserId != tg) return;")
                        && queue.indexOf("api.queueFinal(identity, bodies);") > queue.indexOf("identity.telegramUserId != tg"),
                "SETUP: the final send is the user's pending withdrawals, queued only for the slot's own linked user");
        String logout = squeeze(body(customer, "public static void onLogout(final int account, final long telegramUserId)"));
        int logoutSend = logout.indexOf("queueFinalWithdrawals(account, telegramUserId);");
        check(logoutSend >= 0 && logoutSend < logout.indexOf("Runnable purge"), "a logout queues the final send first, then purges");

        String screen = code(new String(Files.readAllBytes(root.resolve(PRO_SCREEN)), StandardCharsets.UTF_8));
        String confirm = squeeze(body(screen, "private void confirmUnlink()"));
        int rechecked = confirm.indexOf("if (!alive()) return;");
        int send = confirm.indexOf("ZenlyCustomer.onUserUnlink(currentAccount);");
        int revoke = confirm.indexOf("pro.unlink(");
        check(rechecked >= 0 && revoke > rechecked, "SETUP: the confirmed Unlink re-checks the screen, then unlinks");
        check(send > rechecked && send < revoke, "the confirmed Unlink queues the final send before pro.unlink drops the session and "
                + "queues bind/revoke");
        check(squeeze(screen).split("ZenlyCustomer\\.onUserUnlink\\(", -1).length == 2 && squeeze(screen).split("\\.unlink\\(", -1).length == 2,
                "the Pro screen has one Unlink, and it sends first");
        // ZENLYGRAM 13.2.1: the automatic link is suppressed in the same turn, after the unlink call and outside its callback
        // (the callback is dropped once the screen is closed; iOS writes the suppression synchronously too).
        String revokeCallback = block(confirm, revoke);
        int suppressed = confirm.indexOf("ProAutoLink.noteUserUnlinked(currentAccount);");
        check(suppressed > confirm.indexOf(revokeCallback, revoke) + revokeCallback.length(),
                "the confirmed Unlink suppresses the automatic link at once, not when the revoke answers");
        String unlink = squeeze(body(customer, "public static void onUserUnlink(final int account)"));
        check(unlink.equals("{ long tg = activatedUser(account); if (tg > 0) queueFinalWithdrawals(account, tg); }"),
                "a user Unlink queues the same final send for the slot's user at once (never re-posted, so it stays ahead of the "
                        + "revoke) and purges nothing: " + unlink);
        check(squeeze(customer).split("queueFinalWithdrawals\\(", -1).length == 4,
                "queueFinalWithdrawals is declared once and called by the logout and the user Unlink only");

        // ZENLYGRAM 13.2.1: performLogout calls the one hook ZgBackend.onLogout before clearConfig; the order lives there.
        String controller = code(new String(Files.readAllBytes(root.resolve(MESSAGES_CONTROLLER)), StandardCharsets.UTF_8));
        String performLogout = squeeze(body(controller, "public void performLogout(int type)"));
        int hook = performLogout.indexOf("org.telegram.zenlygram.cloud.ZgBackend.onLogout(currentAccount, getUserConfig().getClientUserId());");
        check(hook >= 0 && hook < performLogout.indexOf("getUserConfig().clearConfig();"), "a logout calls the cloud hook before clearConfig");
        String backend = code(new String(Files.readAllBytes(root.resolve(ZG_BACKEND)), StandardCharsets.UTF_8));
        String backendLogout = squeeze(body(backend, "public static void onLogout(int account, long userId)"));
        int onLogout = backendLogout.indexOf("ZenlyCustomer.onLogout(account, userId);");
        int logoutUnlink = backendLogout.indexOf("ZenlyPro.get(account).unlink();");
        check(onLogout >= 0 && logoutUnlink > onLogout, "a logout queues the final send before ZenlyPro.unlink");

        String api = code(new String(Files.readAllBytes(root.resolve(CUSTOMER_API)), StandardCharsets.UTF_8));
        String queueFinal = squeeze(body(api, "public boolean queueFinal(Identity expected, List<byte[]> consentBodies)"));
        int captured = queueFinal.indexOf("captured = boundary.captureFinal(expected);");
        check(captured >= 0 && queueFinal.indexOf("boundary.execute(Lane.USER, () -> {") > captured,
                "queueFinal captures the session, then queues one task on the user lane before it returns");
        String pro = code(new String(Files.readAllBytes(root.resolve(ZENLY_PRO)), StandardCharsets.UTF_8));
        check(squeeze(pro).contains("(lane == CustomerApi.Lane.USER ? WORKER : CUSTOMER_WORKER).execute(work);"),
                "the user lane is WORKER");
        String proUnlink = squeeze(body(pro, "public void unlink(Callback<Snapshot> callback)"));
        String locked = block(proUnlink, proUnlink.indexOf("synchronized (LOCK)"));
        check(locked.indexOf("session = null;") >= 0 && locked.indexOf("WORKER.execute(() -> {") > locked.indexOf("session = null;"),
                "unlink drops the session and queues bind/revoke on WORKER after it, under LOCK: after the final send's task");
    }

    /** ZenlyCustomer.stillGranted as the source states it, without the deletion gate (none pending here). */
    private static boolean stillGranted(ConsentSnapshot now, String grant) {
        return grant != null && now.recordingAllowed() && now.record.grant != null && grant.equals(now.record.grant.consentId);
    }

    // ---- helpers ----

    /** Every fact of a confirmed, ready account. */
    private static CustomerPipeline.Facts ready() {
        CustomerPipeline.Facts f = new CustomerPipeline.Facts();
        f.state = ConsentMachine.State.GRANTED;
        f.linked = true;
        f.grantAcked = true;
        f.profileSettled = true;
        f.confirmed = true;
        return f;
    }

    private static boolean bit(int mask, int index) { return (mask & (1 << index)) != 0; }

    private static ConsentSnapshot granted(long at) {
        return ConsentMachine.grant(ConsentSnapshot.empty(TG, 1), TEXT, ConsentMachine.Mode.REQUIRED, id(++ids), at, "1.0.0", AUTH).snapshot;
    }

    private static ConsentSnapshot withdraw(ConsentSnapshot s, long at) {
        ConsentMachine.Step step = ConsentMachine.withdraw(s, ConsentMachine.Mode.REQUIRED, "uz", id(++ids), at, "1.0.0", AUTH);
        check(step.changed, "withdrawal helper");
        return step.snapshot;
    }

    private static ConsentSnapshot acked(ConsentSnapshot s, String authority) {
        List<ConsentMachine.Result> results = new ArrayList<>();
        for (ConsentEvent event : s.outbox) results.add(new ConsentMachine.Result(event.consentId, "recorded", false));
        return ConsentMachine.consentsAnswered(s, authority, results, null, 0).snapshot;
    }

    private static ConsentSnapshot ackOnly(ConsentSnapshot s, String authority, String consentId) {
        return ConsentMachine.consentsAnswered(s, authority, results(consentId, "recorded"), null, 0).snapshot;
    }

    private static ConsentSnapshot reject(ConsentSnapshot s) {
        return ConsentMachine.rejected(s, Collections.singletonList(s.latestGrant().consentId), "consent_text_mismatch").snapshot;
    }

    private static List<ConsentMachine.Result> results(String consentId, String outcome) {
        return Collections.singletonList(new ConsentMachine.Result(consentId, outcome, false));
    }

    private static List<ConsentMachine.Result> resultsOf(List<ConsentEvent> events) {
        List<ConsentMachine.Result> results = new ArrayList<>();
        for (ConsentEvent event : events) results.add(new ConsentMachine.Result(event.consentId, "recorded", false));
        return results;
    }

    private static String id(int n) { return String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", n); }

    /**
     * What ZenlyConsent.setBindingLoss leaves for a fed loss when every commit succeeds: its feed
     * (ConsentMachine.feedBindingLoss: the loss in memory, then the grant it ended stored as needs_reconsent).
     */
    private static ConsentSnapshot fed(ConsentSnapshot s, long lostAt) {
        FeedStore store = new FeedStore(s);
        store.feed(lostAt);
        return store.published;
    }

    /**
     * One account of ZenlyConsent's store, written as ZenlyConsent.update writes it (its lines are pinned in
     * aLaterLinkNeverRevivesTheEndedGrant): an unchanged step is returned as is; a changed one whose stored values differ
     * from the published ones is committed first and published only when that commit succeeded (otherwise null, nothing
     * published); any other changed step is published at once. So {@code published.storedValues()} is always what the
     * last successful commit stored. Each commit takes the next scripted outcome (true: it fails), then succeeds.
     */
    private static final class FeedStore implements ConsentMachine.Store {
        ConsentSnapshot published;
        int commits;
        private final ArrayDeque<Boolean> failures;

        FeedStore(ConsentSnapshot s, Boolean... failures) {
            published = s;
            this.failures = new ArrayDeque<>(Arrays.asList(failures));
        }

        @Override public ConsentMachine.Step update(ConsentMachine.Transition transition) {
            ConsentSnapshot current = published;
            ConsentMachine.Step step = transition.apply(current);
            if (!step.changed) return step;
            if (!step.snapshot.storedValues().equals(current.storedValues())) {
                commits++;
                if (Boolean.TRUE.equals(failures.poll())) return null;
            }
            published = step.snapshot;
            return step;
        }

        /** ZenlyConsent.setBindingLoss. */
        ConsentMachine.Step feed(long lostAt) { return ConsentMachine.feedBindingLoss(this, lostAt); }
    }

    /**
     * What the next process holds (ZenlyConsent.load, then onPostInit's binding loss through ZenlyConsent.setBindingLoss):
     * only the stored values survive.
     */
    private static ConsentSnapshot restart(ConsentSnapshot s, long lostAt) {
        Object record = null, outbox = null, rejected = null;
        Map<String, Object> acked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : s.storedValues().entrySet()) {
            ConsentSnapshot.Keys.Key key = ConsentSnapshot.Keys.parse(entry.getKey());
            check(key != null && key.tgUserId == s.tgUserId, "a stored key of the user: " + entry.getKey());
            Object tree = MiniJson.parse(entry.getValue());
            if (key.kind == ConsentSnapshot.Keys.Kind.RECORD) {
                record = tree;
            } else if (key.kind == ConsentSnapshot.Keys.Kind.OUTBOX) {
                outbox = tree;
            } else if (key.kind == ConsentSnapshot.Keys.Kind.ACKED) {
                acked.put(key.authority, tree);
            } else {
                rejected = tree;
            }
        }
        ConsentSnapshot loaded = ConsentSnapshot.decode(s.tgUserId, record, outbox, acked, rejected, 0, s.minValidVersion);
        check(loaded.storedValues().equals(s.storedValues()), "the stored values decode to the same snapshot");
        return fed(loaded, lostAt);
    }

    /** Java source with every comment and the contents of every string and char literal blanked (same length). */
    private static String code(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("//", i) || source.startsWith("/*", i)) {
                boolean line = source.startsWith("//", i);
                int end = line ? source.indexOf('\n', i) : source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : line ? end : end + 2;
                for (; i < end; i++) out.append(source.charAt(i) == '\n' ? '\n' : ' ');
            } else if (c == '"' || c == '\'') {
                out.append(c);
                for (i++; i < source.length() && source.charAt(i) != c && source.charAt(i) != '\n'; i++) {
                    if (source.charAt(i) == '\\' && i + 1 < source.length()) {
                        out.append(' ');
                        i++;
                    }
                    out.append(' ');
                }
                if (i < source.length()) out.append(source.charAt(i++));
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The braces-included body of the one method of {@code code} declared as {@code header}. */
    private static String body(String code, String header) {
        int at = code.indexOf(header);
        check(at >= 0 && code.indexOf(header, at + 1) < 0, "exactly one declaration: " + header);
        return block(code, at);
    }

    /** The braces-included block that opens at the first brace at or after {@code from}. */
    private static String block(String code, int from) {
        int open = code.indexOf('{', from);
        int depth = 0;
        for (int i = open; open >= 0 && i < code.length(); i++) {
            if (code.charAt(i) == '{') {
                depth++;
            } else if (code.charAt(i) == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("no balanced block after offset " + from);
    }

    private static String squeeze(String code) { return code.replaceAll("\\s+", " ").trim(); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
