package org.telegram.messenger.zenlygram.customer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.telegram.messenger.zenlygram.customer.ConsentMachine.Mode;
import org.telegram.messenger.zenlygram.customer.ConsentMachine.Notice;
import org.telegram.messenger.zenlygram.customer.ConsentMachine.State;
import org.telegram.messenger.zenlygram.customer.ConsentMachine.Step;
import org.telegram.messenger.zenlygram.customer.ConsentMachine.Wall;

/**
 * Plain-JVM checks of the consent state machine (contract §6.2–§6.3; design 50 §5.4–§5.6 and the A3 integration rows):
 * every §5.5 row — grant from each state, optional decline (Back) and the required no-op, withdraw incl. from
 * needs_reconsent / declined / a rejected latest grant only with an acked earlier grant, the 403 reconcile, the consents
 * answers (erasure mitigation, remote withdrawal, superseded withdraw → active elsewhere), terminal rejection, binding
 * loss and the grant it ended stored as needs_reconsent so that a later link never revives it (compliance X-7(b)), 409
 * consent_stale, 401 account_erased / logout (forget), the retired version — the wall predicate, the cases in
 * which grantJsonIfGranted is null, monotonic instants, snapshot immutability, the outbox bound, the upload batches and
 * the store codec (exact stored JSON, keys, corrupt values); and the stale-grant rule of iOS (design 40 §4.6 rows 5–6: a
 * retired text version, or a text this build renders with another hash) for an account that is not the selected one.
 * Run with run-tests.sh, which passes the Android root.
 */
public final class ConsentMachineTests {
    private static int checks;
    private static final long TG = 555000111L;
    /** A second signed-in account, never the selected one. */
    private static final long TG2 = 555000222L;
    private static final String AUTH = "zenlygram.org";
    private static final String OTHER = "10.0.2.2";
    private static final String SHA = "229fd61b5ac4c2711e1b9635b6e5b24ab44ab5564baac8a2c1067a5b127f64d9";
    private static final ConsentText.Shown TEXT = new ConsentText.Shown(1, ConsentManifest.SCOPES, "uz", "# Rozilik", SHA);
    private static final String SHA2 = "996d5f8db11e5e06d2f23ccefc4a691cca16019b4e77456c26fb243a856d5ecd";
    private static final long T0 = 1793268000L; // 2026-10-29T10:00:00Z
    private static int ids;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ConsentMachineTests <android repository root>");
        Path fixtures = Paths.get(args[0]).resolve("zenlygram-tests/customer/fixtures");
        Path assets = Paths.get(args[0]).resolve("TMessagesProj/src/main/assets/zenlygram/consent");
        wallPredicate();
        grantRows();
        declineRows();
        withdrawRows();
        consentRequiredRows();
        consentsAnsweredRows();
        rejectedRows();
        bindingLossRows();
        linkEndedRows();
        consentStaleRows();
        forgetRows();
        retiredVersion();
        retiredVersionOnEveryAccount();
        changedTextOnEveryAccount(assets);
        renderedHashes(assets);
        grantJsonIfGrantedNulls();
        monotonicInstants();
        immutability();
        outboxBound();
        uploadBatches();
        codec();
        answers(fixtures);
        System.out.println("Consent machine: " + checks + " checks passed");
    }

    // ---- §5.6 ----

    private static void wallPredicate() {
        for (State state : State.values()) {
            check(ConsentMachine.wall(Mode.REQUIRED, state) == (state == State.GRANTED ? Wall.NONE : Wall.BLOCKING), "required wall for " + state);
            boolean prompt = state == State.NONE || state == State.NEEDS_RECONSENT;
            check(ConsentMachine.wall(Mode.OPTIONAL, state) == (prompt ? Wall.PROMPT : Wall.NONE), "optional wall for " + state);
        }
        check(Mode.of("optional") == Mode.OPTIONAL && Mode.of("required") == Mode.REQUIRED && Mode.of("maybe") == Mode.REQUIRED
                && Mode.of(null) == Mode.REQUIRED, "anything but optional is required");
        check(State.of("withdrawn_remote") == State.WITHDRAWN_REMOTE && State.of("Granted") == null && State.of(null) == null, "state names");
    }

    // ---- grant ----

    private static void grantRows() {
        ConsentSnapshot empty = empty();
        Step step = ConsentMachine.grant(empty, TEXT, Mode.REQUIRED, id(1), T0, "1.0.0", AUTH);
        ConsentSnapshot s = step.snapshot;
        check(step.changed && !step.purgeEvents && s.record.state == State.GRANTED && s.effectiveState() == State.GRANTED, "none → granted");
        check(("{\"v\":1,\"tg_user_id\":555000111,\"state\":\"granted\",\"consent_id\":\"" + id(1) + "\",\"text_version\":1,\"text_sha256\":\""
                + SHA + "\",\"locale\":\"uz\",\"consent_mode\":\"required\",\"decided_at\":\"2026-10-29T10:00:00Z\",\"changed_at\":"
                + "\"2026-10-29T10:00:00Z\"}").equals(s.recordJson()), "the exact stored record");
        ConsentEvent event = s.outbox.get(0);
        check(s.outbox.size() == 1 && event.isGrant() && event.consentId.equals(id(1)) && event.recordedAt == T0 && event.textVersion == 1
                && SHA.equals(event.textSha256) && "uz".equals(event.locale) && "required".equals(event.consentMode)
                && event.scopes.equals(ConsentManifest.SCOPES) && "1.0.0".equals(event.appVersion), "the grant event is in the outbox");
        check(event.json().equals(s.latestGrantJson()) && event.json().equals(s.grantJsonIfGranted(AUTH)) && s.recordingAllowed(),
                "an effective grant is usable");
        check(s.notice() == Notice.NONE && s.wall(Mode.REQUIRED) == Wall.NONE && s.wall(Mode.OPTIONAL) == Wall.NONE, "no wall once granted");
        check(empty.record.state == State.NONE && empty.outbox.isEmpty() && empty.storedValues().isEmpty(), "the old snapshot is untouched");
        Step again = ConsentMachine.grant(s, TEXT, Mode.REQUIRED, id(2), T0 + 5, "1.0.0", AUTH);
        check(!again.changed && again.snapshot == s && !s.grantable(), "an effective grant is not granted again");
        // Every other starting state grants (design 50 §5.5 first row).
        Map<String, ConsentSnapshot> starts = new LinkedHashMap<>();
        starts.put("declined", ConsentMachine.decline(empty(), Mode.OPTIONAL, T0).snapshot);
        starts.put("withdrawn", acked(withdraw(granted(T0), T0 + 10), AUTH));
        starts.put("withdrawn_remote", ConsentMachine.consentRequired(granted(T0), "withdrawn", T0 + 60, T0 + 70).snapshot);
        starts.put("needs_reconsent (stored)", ConsentMachine.consentRequired(granted(T0), "reconsent_required", 0, T0 + 10).snapshot);
        starts.put("needs_reconsent (stale)", stale(granted(T0), T0 + 10));
        starts.put("needs_reconsent (binding loss)", ConsentMachine.bindingLoss(granted(T0), T0 + 10).snapshot);
        starts.put("needs_reconsent (link ended, stored)", fed(granted(T0), T0 + 10));
        starts.put("needs_reconsent (retired version)", granted(T0).withMinValidVersion(2));
        starts.put("granted with a rejected latest grant", reject(granted(T0)));
        for (Map.Entry<String, ConsentSnapshot> start : starts.entrySet()) {
            ConsentSnapshot before = start.getValue();
            check(before.effectiveState() != State.GRANTED || before.latestRejected(), start.getKey() + " is not an effective grant");
            ConsentText.Shown text = before.minValidVersion == 2 ? new ConsentText.Shown(2, ConsentManifest.SCOPES, "ru", "v2", SHA) : TEXT;
            Step regrant = ConsentMachine.grant(before, text, Mode.REQUIRED, id(900), T0 + 20, "1.0.0", AUTH);
            ConsentSnapshot after = regrant.snapshot;
            check(regrant.changed && after.effectiveState() == State.GRANTED && after.recordingAllowed() && after.grantJsonIfGranted(AUTH) != null
                    && after.latestGrant().consentId.equals(id(900)) && after.notice() == Notice.NONE && !after.record.activeElsewhere,
                    start.getKey() + " → granted");
        }
    }

    // ---- decline ----

    private static void declineRows() {
        Step step = ConsentMachine.decline(empty(), Mode.OPTIONAL, T0);
        ConsentSnapshot s = step.snapshot;
        check(step.changed && !step.purgeEvents && s.record.state == State.DECLINED && s.record.grant == null && s.outbox.isEmpty(),
                "optional: none → declined (Back, close or I don't agree), nothing to upload");
        check("{\"v\":1,\"tg_user_id\":555000111,\"state\":\"declined\",\"changed_at\":\"2026-10-29T10:00:00Z\"}".equals(s.recordJson()),
                "a declined record without a grant");
        check(s.wall(Mode.OPTIONAL) == Wall.NONE && s.wall(Mode.REQUIRED) == Wall.BLOCKING && s.grantJsonIfGranted(AUTH) == null
                && !s.recordingAllowed(), "declined: no prompt in optional, walled in a required build");
        ConsentSnapshot stored = ConsentMachine.consentRequired(granted(T0), "reconsent_required", 0, T0 + 5).snapshot;
        ConsentSnapshot declined = ConsentMachine.decline(stored, Mode.OPTIONAL, T0 + 10).snapshot;
        check(declined.record.state == State.DECLINED && declined.record.grant != null && declined.record.grant.consentId.equals(stored.record.grant.consentId),
                "optional: needs_reconsent → declined keeps the latest grant's fields");
        ConsentSnapshot lost = ConsentMachine.bindingLoss(granted(T0), T0 + 5).snapshot;
        check(ConsentMachine.decline(lost, Mode.OPTIONAL, T0 + 10).snapshot.record.state == State.DECLINED, "optional: derived needs_reconsent → declined");
        check(!ConsentMachine.decline(empty(), Mode.REQUIRED, T0).changed && !ConsentMachine.decline(stored, Mode.REQUIRED, T0).changed,
                "required: I don't agree records nothing");
        for (ConsentSnapshot other : Arrays.asList(granted(T0), withdraw(granted(T0), T0 + 1), s)) {
            check(!ConsentMachine.decline(other, Mode.OPTIONAL, T0 + 9).changed, "optional: no decline from " + other.record.state);
        }
    }

    // ---- withdraw ----

    private static void withdrawRows() {
        ConsentSnapshot granted = granted(T0);
        Step step = ConsentMachine.withdraw(granted, Mode.REQUIRED, "ru", id(50), T0 + 30, "1.0.1", AUTH);
        ConsentSnapshot s = step.snapshot;
        ConsentEvent event = s.outbox.get(1);
        check(step.changed && step.purgeEvents && s.record.state == State.WITHDRAWN && s.notice() == Notice.WITHDRAWN, "granted → withdrawn, purge");
        check(event.isWithdraw() && event.consentId.equals(id(50)) && event.scopes.equals(ConsentManifest.SCOPES) && event.textVersion == 1
                && "ru".equals(event.locale) && "required".equals(event.consentMode) && event.recordedAt == T0 + 30 && event.textSha256 == null,
                "the withdraw event: all scopes, the grant's version, the screen's locale");
        check(s.record.grant.consentId.equals(granted.record.grant.consentId) && s.record.changedAt == T0 + 30, "the grant fields stay");
        check(s.pendingWithdraw(AUTH) && s.grantJsonIfGranted(AUTH) == null && !s.recordingAllowed() && s.wall(Mode.REQUIRED) == Wall.BLOCKING
                && s.wall(Mode.OPTIONAL) == Wall.NONE, "withdrawn: walled in required mode, local only in optional mode");
        check(!ConsentMachine.withdraw(s, Mode.REQUIRED, "uz", id(51), T0 + 40, "1", AUTH).changed, "no withdrawal from withdrawn");
        check(!ConsentMachine.withdraw(empty(), Mode.REQUIRED, "uz", id(51), T0, "1", AUTH).changed, "no withdrawal from none");
        ConsentSnapshot remote = ConsentMachine.consentRequired(granted(T0), "withdrawn", T0 + 60, T0 + 70).snapshot;
        check(!ConsentMachine.withdraw(remote, Mode.REQUIRED, "uz", id(51), T0 + 80, "1", AUTH).changed, "no withdrawal from withdrawn_remote");
        // needs_reconsent / declined / a rejected latest grant: only when an earlier grant was acked somewhere.
        ConsentSnapshot reconsent = ConsentMachine.consentRequired(granted(T0), "reconsent_required", 0, T0 + 5).snapshot;
        check(!reconsent.withdrawable() && !ConsentMachine.withdraw(reconsent, Mode.REQUIRED, "uz", id(52), T0 + 9, "1", AUTH).changed,
                "needs_reconsent without an acked grant: nothing to withdraw on the server");
        ConsentSnapshot ackedReconsent = ConsentMachine.consentRequired(acked(granted(T0), OTHER), "reconsent_required", 0, T0 + 5).snapshot;
        check(ackedReconsent.withdrawable() && ConsentMachine.withdraw(ackedReconsent, Mode.REQUIRED, "uz", id(52), T0 + 9, "1", AUTH)
                .snapshot.record.state == State.WITHDRAWN, "needs_reconsent with a grant acked at another authority → withdrawn");
        ConsentSnapshot lost = ConsentMachine.bindingLoss(acked(granted(T0), AUTH), T0 + 5).snapshot;
        check(lost.effectiveState() == State.NEEDS_RECONSENT && lost.withdrawable(), "a binding loss after an acked grant may still be withdrawn");
        ConsentSnapshot declined = ConsentMachine.decline(ackedReconsent, Mode.OPTIONAL, T0 + 7).snapshot;
        check(declined.record.state == State.DECLINED && declined.withdrawable()
                && ConsentMachine.withdraw(declined, Mode.OPTIONAL, "en", id(53), T0 + 9, "1", AUTH).snapshot.record.state == State.WITHDRAWN,
                "declined with an acked earlier grant → withdrawn");
        ConsentSnapshot neverGranted = ConsentMachine.decline(empty(), Mode.OPTIONAL, T0).snapshot;
        check(!neverGranted.withdrawable() && !ConsentMachine.withdraw(neverGranted, Mode.OPTIONAL, "uz", id(54), T0 + 1, "1", AUTH).changed,
                "declined without any grant: no withdrawal");
        ConsentSnapshot rejectedOnly = reject(granted(T0));
        check(!rejectedOnly.withdrawable(), "a rejected latest grant alone is not withdrawn (the server holds nothing)");
        ConsentSnapshot earlierAcked = acked(granted(T0), AUTH);
        earlierAcked = ConsentMachine.consentRequired(earlierAcked, "reconsent_required", 0, T0 + 5).snapshot;
        earlierAcked = ConsentMachine.grant(earlierAcked, TEXT, Mode.REQUIRED, id(55), T0 + 10, "1", AUTH).snapshot;
        earlierAcked = reject(earlierAcked);
        check(earlierAcked.effectiveState() == State.GRANTED && earlierAcked.latestRejected() && earlierAcked.withdrawable()
                && ConsentMachine.withdraw(earlierAcked, Mode.REQUIRED, "uz", id(56), T0 + 20, "1", AUTH).snapshot.record.state == State.WITHDRAWN,
                "granted with a rejected latest grant and an acked earlier one → withdrawn");
    }

    // ---- 403 consent_required ----

    private static void consentRequiredRows() {
        ConsentSnapshot granted = granted(T0);
        Step remote = ConsentMachine.consentRequired(granted, "withdrawn", T0 + 1, T0 + 100);
        check(remote.changed && remote.purgeEvents && remote.snapshot.record.state == State.WITHDRAWN_REMOTE && remote.snapshot.record.changedAt == T0 + 1
                && remote.snapshot.notice() == Notice.WITHDRAWN_REMOTE && remote.snapshot.grantJsonIfGranted(AUTH) == null,
                "withdrawn newer than the grant → withdrawn_remote, dated at effective_at, purge");
        check(remote.snapshot.wall(Mode.REQUIRED) == Wall.BLOCKING && remote.snapshot.wall(Mode.OPTIONAL) == Wall.NONE, "withdrawn_remote walls");
        for (long at : new long[] {T0, T0 - 1, 0}) {
            Step older = ConsentMachine.consentRequired(granted, "withdrawn", at, T0 + 100);
            check(!older.changed && older.reuploadGrant && !older.purgeEvents && older.snapshot == granted, "withdrawn not newer → re-upload the grant");
        }
        Step noConsent = ConsentMachine.consentRequired(granted, "no_consent", 0, T0 + 100);
        check(!noConsent.changed && noConsent.uploadThenRetry && !noConsent.reuploadGrant, "no_consent → upload the outbox, retry once");
        Step reconsent = ConsentMachine.consentRequired(granted, "reconsent_required", 0, T0 + 100);
        check(reconsent.changed && !reconsent.purgeEvents && reconsent.snapshot.record.state == State.NEEDS_RECONSENT
                && reconsent.snapshot.notice() == Notice.RECONSENT && reconsent.snapshot.recordJson().contains("\"notice\":\"reconsent\""),
                "reconsent_required → needs_reconsent (stored, RECONSENT)");
        check(!ConsentMachine.consentRequired(granted, "something_else", 0, T0).changed, "an unknown reason changes nothing");
        ConsentSnapshot withdrawn = withdraw(granted, T0 + 5);
        check(!ConsentMachine.consentRequired(withdrawn, "withdrawn", T0 + 50, T0 + 60).changed, "only a stored grant reconciles");
    }

    // ---- consents 200 ----

    private static void consentsAnsweredRows() {
        ConsentSnapshot granted = granted(T0);
        String grantId = granted.record.grant.consentId;
        ConsentMachine.Effective effectiveGranted = new ConsentMachine.Effective("granted", grantId, 1, T0);
        Step ack = ConsentMachine.consentsAnswered(granted, AUTH, results(grantId, "recorded"), effectiveGranted, T0 + 1);
        check(ack.changed && !ack.purgeEvents && ack.snapshot.isAcked(AUTH, grantId) && !ack.snapshot.isAcked(OTHER, grantId)
                && ack.snapshot.pending(AUTH).isEmpty() && ack.snapshot.pending(OTHER).size() == 1 && ack.snapshot.record.state == State.GRANTED,
                "recorded → acked at that authority only");
        check(ack.snapshot.storedValues().get("consent.acked.v1.555000111@zenlygram.org").equals("[\"" + grantId + "\"]"), "the stored acked set");
        check(!ConsentMachine.consentsAnswered(ack.snapshot, AUTH, results(grantId, "duplicate"), effectiveGranted, T0 + 2).changed,
                "a repeated ack changes nothing");
        check(!ConsentMachine.consentsAnswered(granted, AUTH, results(id(777), "recorded"), null, T0).changed, "an unknown consent id is ignored");
        check(!ConsentMachine.consentsAnswered(granted, null, results(grantId, "recorded"), null, T0).changed, "no authority, no ack");
        for (String outcome : new String[] {"recorded", "duplicate"}) {
            List<ConsentMachine.Result> answer = Collections.singletonList(new ConsentMachine.Result(grantId, outcome, "duplicate".equals(outcome)));
            Step erased = ConsentMachine.consentsAnswered(granted, AUTH, answer, new ConsentMachine.Effective("none", null, 0, 0), T0 + 9);
            check(erased.changed && erased.purgeEvents && erased.snapshot.record.state == State.NEEDS_RECONSENT
                    && erased.snapshot.notice() == Notice.LINK_ENDED && erased.snapshot.isAcked(AUTH, grantId)
                    && erased.snapshot.recordJson().contains("\"notice\":\"link_ended\""),
                    "our grant " + outcome + " while the effective state is none → needs_reconsent (erasure mitigation), purge");
        }
        Step remote = ConsentMachine.consentsAnswered(granted, AUTH, results(grantId, "superseded"),
                new ConsentMachine.Effective("withdrawn", id(600), 1, T0 + 40), T0 + 50);
        check(remote.changed && remote.purgeEvents && remote.snapshot.record.state == State.WITHDRAWN_REMOTE && remote.snapshot.record.changedAt == T0 + 40,
                "an effective withdrawal newer than the grant → withdrawn_remote");
        Step older = ConsentMachine.consentsAnswered(granted, AUTH, results(grantId, "recorded"),
                new ConsentMachine.Effective("withdrawn", id(600), 1, T0 - 40), T0 + 50);
        check(older.changed && !older.purgeEvents && older.snapshot.record.state == State.GRANTED, "an older effective withdrawal only acks");
        // Our withdraw answered superseded while a later grant (another device) is effective.
        ConsentSnapshot withdrawn = withdraw(acked(granted, AUTH), T0 + 10);
        String withdrawId = withdrawn.latestWithdrawEvent().consentId;
        Step elsewhere = ConsentMachine.consentsAnswered(withdrawn, AUTH, results(withdrawId, "superseded"),
                new ConsentMachine.Effective("granted", id(700), 1, T0 + 20), T0 + 30);
        check(elsewhere.changed && !elsewhere.purgeEvents && elsewhere.snapshot.record.state == State.WITHDRAWN && elsewhere.snapshot.record.activeElsewhere
                && elsewhere.snapshot.isAcked(AUTH, withdrawId) && elsewhere.snapshot.recordJson().endsWith(",\"active_elsewhere\":true}")
                && !elsewhere.snapshot.pendingWithdraw(AUTH), "superseded withdraw + effective granted → withdrawn, acked, active elsewhere");
        check(!ConsentMachine.consentsAnswered(elsewhere.snapshot, AUTH, results(withdrawId, "superseded"),
                new ConsentMachine.Effective("granted", id(700), 1, T0 + 20), T0 + 31).changed, "never re-issued, nothing more to do");
        check(!ConsentMachine.consentsAnswered(withdrawn, AUTH, results(withdrawId, "superseded"),
                new ConsentMachine.Effective("withdrawn", id(701), 1, T0 + 20), T0 + 30).snapshot.record.activeElsewhere,
                "superseded by a later withdrawal: nothing active elsewhere");
        check(!ConsentMachine.consentsAnswered(withdrawn, AUTH, results(withdrawId, "recorded"),
                new ConsentMachine.Effective("withdrawn", withdrawId, 1, T0 + 10), T0 + 30).snapshot.record.activeElsewhere,
                "a recorded withdrawal: nothing active elsewhere");
        ConsentSnapshot regranted = ConsentMachine.grant(elsewhere.snapshot, TEXT, Mode.REQUIRED, id(702), T0 + 40, "1", AUTH).snapshot;
        check(!regranted.record.activeElsewhere && !regranted.recordJson().contains("active_elsewhere"), "a new decision clears active elsewhere");
    }

    // ---- terminal rejection ----

    private static void rejectedRows() {
        ConsentSnapshot granted = granted(T0);
        String grantId = granted.record.grant.consentId;
        Step step = ConsentMachine.rejected(granted, Collections.singletonList(grantId), "consent_text_mismatch");
        ConsentSnapshot s = step.snapshot;
        check(step.changed && step.purgeEvents && s.record.state == State.GRANTED && s.effectiveState() == State.GRANTED && s.latestRejected()
                && "consent_text_mismatch".equals(s.latestRejectedCode()), "a terminal 422 rejects the latest grant; the state stays granted");
        check(!s.recordingAllowed() && s.grantJsonIfGranted(AUTH) == null && s.pending(AUTH).isEmpty() && s.nextUpload(AUTH).isEmpty()
                && s.wall(Mode.REQUIRED) == Wall.NONE && s.grantable(), "rejected: no recording, no link consent, never resent, no wall, give consent again");
        check(("{\"" + grantId + "\":\"consent_text_mismatch\"}").equals(s.rejectedJson()), "the stored rejected map");
        check("rejected".equals(ConsentMachine.rejected(granted, Collections.singletonList(grantId), "Not A Code!").snapshot.latestRejectedCode()),
                "a malformed error code is stored as rejected");
        check(!ConsentMachine.rejected(granted, Collections.singletonList(id(888)), "invalid_request").changed
                && !ConsentMachine.rejected(s, Collections.singletonList(grantId), "x").changed, "unknown or already rejected ids change nothing");
        ConsentSnapshot withdrawn = withdraw(acked(granted, AUTH), T0 + 5);
        Step withdrawRejected = ConsentMachine.rejected(withdrawn, Collections.singletonList(withdrawn.latestWithdrawEvent().consentId), "profile_account_mismatch");
        check(withdrawRejected.changed && !withdrawRejected.purgeEvents && !withdrawRejected.snapshot.pendingWithdraw(AUTH),
                "a rejected withdrawal (409 profile_account_mismatch) is never resent");
    }

    // ---- binding loss (design 50 §7.6) ----

    private static void bindingLossRows() {
        ConsentSnapshot granted = granted(T0);
        Step lost = ConsentMachine.bindingLoss(granted, T0);
        ConsentSnapshot s = lost.snapshot;
        check(lost.changed && lost.purgeEvents && s.effectiveState() == State.NEEDS_RECONSENT && s.record.state == State.GRANTED
                && s.notice() == Notice.LINK_ENDED, "a loss at decided_at → derived needs_reconsent (LINK_ENDED), purge");
        check(s.storedValues().equals(granted.storedValues()),
                "the loss itself stays in memory (binding.v1 holds it); linkEnded stores the grant it ended (linkEndedRows)");
        check(s.wall(Mode.REQUIRED) == Wall.BLOCKING && s.wall(Mode.OPTIONAL) == Wall.PROMPT && !s.recordingAllowed() && s.grantJsonIfGranted(AUTH) == null,
                "a binding loss after the grant walls (required) or prompts (optional), no link consent");
        Step earlier = ConsentMachine.bindingLoss(granted, T0 - 1);
        check(earlier.changed && !earlier.purgeEvents && earlier.snapshot.effectiveState() == State.GRANTED, "a loss before the grant does not count");
        Step cleared = ConsentMachine.bindingLoss(s, 0);
        check(cleared.changed && !cleared.purgeEvents && cleared.snapshot.bindingLostAt == 0
                        && cleared.snapshot.record.state == State.NEEDS_RECONSENT && cleared.snapshot.notice() == Notice.LINK_ENDED,
                "0 clears the in-memory loss (a later link) but never lifts the grant it ended: that grant is stored as ended in "
                        + "the same step (linkEndedRows)");
        Step clearedEarlier = ConsentMachine.bindingLoss(earlier.snapshot, 0);
        check(clearedEarlier.changed && clearedEarlier.snapshot.effectiveState() == State.GRANTED
                && clearedEarlier.snapshot.storedValues().equals(granted.storedValues()), "clearing a loss that ended no grant stores nothing");
        check(!ConsentMachine.bindingLoss(s, T0).changed, "the same loss again changes nothing");
        ConsentSnapshot regranted = ConsentMachine.grant(s, TEXT, Mode.REQUIRED, id(300), T0 - 100, "1", AUTH).snapshot;
        check(regranted.effectiveState() == State.GRANTED && regranted.latestGrant().decidedAt > s.bindingLostAt,
                "a new grant lifts the loss even with a clock behind");
    }

    // ---- the grant a binding loss ended stays ended (compliance design 60 §8 X-7(b); C-13, C-21; iOS design 40 §8.1) ----

    /**
     * ZenlyConsent.setBindingLoss publishes the loss ({@link ConsentMachine#bindingLoss}) and then stores the grant it ended
     * ({@link ConsentMachine#linkEnded}): needs_reconsent (LINK_ENDED) until a new grant, so a later link, which clears
     * the loss, never makes the old consent id effective again.
     */
    private static void linkEndedRows() {
        ConsentSnapshot granted = acked(granted(T0), AUTH);
        String grantId = granted.latestGrant().consentId;
        ConsentSnapshot lost = ConsentMachine.bindingLoss(granted, T0 + 5).snapshot;
        check(lost.effectiveState() == State.NEEDS_RECONSENT && lost.record.state == State.GRANTED && lost.storedValues().equals(granted.storedValues()),
                "SETUP: the loss itself is in memory only (published without a commit)");

        Step step = ConsentMachine.linkEnded(lost);
        ConsentSnapshot s = step.snapshot;
        check(step.changed && !step.purgeEvents && s.record.state == State.NEEDS_RECONSENT && s.notice() == Notice.LINK_ENDED
                && s.record.changedAt == T0 + 5 && s.latestGrant().consentId.equals(grantId),
                "a loss after the grant: that grant is stored needs_reconsent (LINK_ENDED), dated at the loss (the purge was the loss's step)");
        check(s.recordJson().contains("\"state\":\"needs_reconsent\"") && s.recordJson().contains("\"notice\":\"link_ended\"")
                && s.recordJson().contains("\"consent_id\":\"" + grantId + "\"") && !s.storedValues().equals(granted.storedValues()),
                "... in the stored record, the format a 409 consent_stale writes");
        check(s.outbox.equals(granted.outbox) && s.acked.equals(granted.acked) && s.rejected.equals(granted.rejected) && s.bindingLostAt == T0 + 5,
                "... and nothing else changes");
        check(!ConsentMachine.linkEnded(s).changed && !ConsentMachine.bindingLoss(s, T0 + 5).changed, "stored once: the same loss again changes nothing");
        check(!ConsentMachine.operatorChanged(s, SHA2, T0 + 30).changed && !ConsentMachine.consentStale(s, granted.latestGrantJson(), T0 + 30).changed,
                "the launch's changed-text pass and a late consent_stale leave it as stored");
        // A loss kept in memory alone (the commit of the grant it ended failed) is not lifted by a later link either:
        // clearing it, or lowering it to an older stale_at, stores that grant in the same step (one commit, review2).
        for (long lower : new long[]{0, T0 - 1}) {
            Step clear = ConsentMachine.bindingLoss(lost, lower);
            check(clear.changed && !clear.purgeEvents && clear.snapshot.bindingLostAt == lower && clear.snapshot.storedValues().equals(s.storedValues())
                    && clear.snapshot.effectiveState() == State.NEEDS_RECONSENT && grantJson(clear.snapshot) == null
                    && !clear.snapshot.recordingAllowed(), "a memory-only loss lowered to " + lower + " stores the grant it ended with it");
        }

        // A later link no longer lifts it. ZenlyCustomer.adopted's order: the new binding clears its acked set, then 0 is
        // fed (Binding.adopted cleared lost_at).
        ConsentSnapshot linked = fed(ConsentMachine.clearAcked(s, AUTH).snapshot, 0);
        check(linked.bindingLostAt == 0 && linked.effectiveState() == State.NEEDS_RECONSENT && linked.notice() == Notice.LINK_ENDED,
                "a later link clears the loss, but the grant stays ended");
        check(grantJson(linked) == null && !linked.recordingAllowed() && linked.wall(Mode.REQUIRED) == Wall.BLOCKING
                && linked.wall(Mode.OPTIONAL) == Wall.PROMPT, "... no consent on bind/start, nothing recorded, the wall or the prompt stays");
        ConsentSnapshot restarted = decodeStored(linked.storedValues(), 0);
        check(restarted.effectiveState() == State.NEEDS_RECONSENT && restarted.notice() == Notice.LINK_ENDED && grantJson(restarted) == null,
                "... after a restart too, with no loss left in binding.v1");
        check(fed(granted, T0 + 5).storedValues().equals(s.storedValues()), "the feed ZenlyConsent runs stores exactly that");

        // Only a new grant lifts it: a new consent id, decided after the loss; the old loss fed again leaves it alone.
        ConsentSnapshot regranted = ConsentMachine.grant(linked, TEXT, Mode.REQUIRED, id(500), T0 - 100, "1.0.0", AUTH).snapshot;
        check(regranted.effectiveState() == State.GRANTED && regranted.latestGrant().decidedAt > T0 + 5
                && !regranted.latestGrant().consentId.equals(grantId) && grantJson(regranted) != null,
                "a new grant lifts it (a new consent id, after the loss even with a clock behind)");
        check(fed(regranted, T0 + 5).record.state == State.GRANTED && fed(regranted, T0 + 5).effectiveState() == State.GRANTED,
                "the old loss fed again at a launch leaves the new grant effective");
        check(ConsentMachine.decline(linked, Mode.OPTIONAL, T0 + 20).snapshot.record.state == State.DECLINED,
                "optional: the prompt's decline records declined");
        check(s.withdrawable() && ConsentMachine.withdraw(s, Mode.REQUIRED, "uz", id(501), T0 + 20, "1.0.0", AUTH).snapshot.record.state
                == State.WITHDRAWN, "until a new binding, an acked grant the loss ended may still be withdrawn");
        check(!linked.withdrawable() && !ConsentMachine.withdraw(linked, Mode.REQUIRED, "uz", id(502), T0 + 20, "1.0.0", AUTH).changed
                && linked.grantable(), "after the later link Withdraw is not offered: the new binding cleared the acked set, so "
                        + "grantMayBeEffective reads false (iOS keeps it withdrawable; carry-forward, review2); Give consent stays");

        // The boundary, and the records it leaves alone.
        long decided = granted.latestGrant().decidedAt;
        check(fed(granted, decided).record.state == State.NEEDS_RECONSENT && fed(granted, decided).notice() == Notice.LINK_ENDED,
                "a loss at decided_at ends the grant (at or after, as on iOS)");
        check(fed(granted, decided - 1).record.state == State.GRANTED && fed(granted, decided - 1).effectiveState() == State.GRANTED
                && !ConsentMachine.linkEnded(granted).changed, "a loss before the grant, or none, changes nothing");
        ConsentSnapshot rejected = fed(reject(granted(T0)), T0 + 9);
        check(rejected.record.state == State.NEEDS_RECONSENT && rejected.notice() == Notice.LINK_ENDED && rejected.latestRejected() && rejected.grantable(),
                "a granted record whose latest grant was rejected is stored as ended too; Give consent mints a new grant");
        ConsentSnapshot retired = fed(granted(T0).withMinValidVersion(2), T0 + 9);
        check(retired.record.state == State.NEEDS_RECONSENT && retired.notice() == Notice.LINK_ENDED, "a retired grant whose link ended: stored with that notice");
        Map<String, ConsentSnapshot> others = new LinkedHashMap<>();
        ConsentSnapshot reconsent = ConsentMachine.consentRequired(granted, "reconsent_required", 0, T0 + 1).snapshot;
        others.put("none", empty());
        others.put("declined", ConsentMachine.decline(reconsent, Mode.OPTIONAL, T0 + 2).snapshot);
        others.put("withdrawn", withdraw(granted, T0 + 1));
        others.put("withdrawn_remote", ConsentMachine.consentRequired(granted(T0), "withdrawn", T0 + 1, T0 + 2).snapshot);
        others.put("needs_reconsent (reconsent)", reconsent);
        others.put("needs_reconsent (stale)", stale(granted(T0), T0 + 1));
        for (Map.Entry<String, ConsentSnapshot> other : others.entrySet()) {
            ConsentSnapshot before = ConsentMachine.bindingLoss(other.getValue(), T0 + 50).snapshot;
            check(!ConsentMachine.linkEnded(before).changed && before.storedValues().equals(other.getValue().storedValues()),
                    other.getKey() + " is left as stored");
        }
        check(fed(reconsent, T0 + 50).notice() == Notice.RECONSENT, "a stored re-consent request keeps its notice, as on iOS");
    }

    // ---- 409 consent_stale (amendment A3) ----

    private static void consentStaleRows() {
        ConsentSnapshot granted = granted(T0);
        String sent = granted.grantJsonIfGranted(AUTH);
        Step step = ConsentMachine.consentStale(granted, sent, T0 + 100);
        ConsentSnapshot s = step.snapshot;
        check(step.changed && step.purgeEvents && s.record.state == State.NEEDS_RECONSENT && s.notice() == Notice.LINK_ENDED
                && s.recordJson().contains("\"state\":\"needs_reconsent\"") && s.recordJson().contains("\"notice\":\"link_ended\""),
                "409 consent_stale for the stored grant → needs_reconsent (stored, LINK_ENDED), purge");
        check(s.wall(Mode.REQUIRED) == Wall.BLOCKING && s.wall(Mode.OPTIONAL) == Wall.PROMPT && s.grantJsonIfGranted(AUTH) == null
                && !s.recordingAllowed() && s.latestGrant().consentId.equals(granted.latestGrant().consentId), "a stale grant is never used again");
        check(!ConsentMachine.consentStale(granted, sent.replace("\"uz\"", "\"ru\""), T0).changed && !ConsentMachine.consentStale(granted, null, T0).changed,
                "only the exact grant that bind/start carried");
        ConsentSnapshot newer = ConsentMachine.grant(reject(granted), TEXT, Mode.REQUIRED, id(400), T0 + 10, "1", AUTH).snapshot;
        check(!ConsentMachine.consentStale(newer, sent, T0 + 20).changed, "a newer grant made meanwhile is never touched");
        check(ConsentMachine.bindingLoss(s, 0).snapshot.effectiveState() == State.NEEDS_RECONSENT, "a manual link does not lift a stale grant");
        ConsentSnapshot regranted = ConsentMachine.grant(s, TEXT, Mode.REQUIRED, id(401), T0 + 50, "1", AUTH).snapshot;
        check(regranted.effectiveState() == State.GRANTED && regranted.latestGrant().decidedAt > s.record.changedAt && regranted.grantJsonIfGranted(AUTH) != null,
                "only a new grant lifts it");
        check(!ConsentMachine.consentStale(withdraw(granted, T0 + 1), sent, T0 + 2).changed, "a withdrawal made meanwhile stands");
    }

    // ---- logout, deletion, 401 account_erased ----

    private static void forgetRows() {
        ConsentSnapshot busy = ConsentMachine.bindingLoss(reject(acked(withdraw(acked(granted(T0), AUTH), T0 + 5), OTHER)), T0 + 9).snapshot;
        check(!busy.isEmpty(), "a snapshot with every part");
        ConsentSnapshot forgotten = ConsentMachine.forget(TG, 1);
        check(forgotten.isEmpty() && forgotten.record.state == State.NONE && forgotten.storedValues().isEmpty() && forgotten.bindingLostAt == 0
                && forgotten.effectiveState() == State.NONE && forgotten.wall(Mode.REQUIRED) == Wall.BLOCKING && forgotten.wall(Mode.OPTIONAL) == Wall.PROMPT,
                "account_erased / logout / deletion: any state → none, nothing stored, the wall shows again");
    }

    // ---- derived: bundled min_valid_version ----

    private static void retiredVersion() {
        ConsentSnapshot granted = granted(T0);
        ConsentSnapshot retired = granted.withMinValidVersion(2);
        check(retired.effectiveState() == State.NEEDS_RECONSENT && retired.notice() == Notice.RECONSENT && retired.record.state == State.GRANTED
                && retired.storedValues().equals(granted.storedValues()), "text_version below min_valid_version → derived needs_reconsent, not stored");
        check(retired.grantJsonIfGranted(AUTH) == null && !retired.recordingAllowed() && retired.wall(Mode.REQUIRED) == Wall.BLOCKING, "a retired grant is not used");
        check(granted.withMinValidVersion(1).effectiveState() == State.GRANTED && granted.withMinValidVersion(0).effectiveState() == State.GRANTED
                && granted.withMinValidVersion(1) == granted, "min_valid_version 1 (or unknown) keeps v1");
        ConsentSnapshot both = ConsentMachine.bindingLoss(retired, T0 + 1).snapshot;
        check(both.notice() == Notice.LINK_ENDED, "a binding loss is explained before a retired version");
    }

    // ---- the stale-grant rule on every signed-in account (iOS parity: design 40 §4.6 rows 5–6) ----

    /**
     * The retired-version half needs no new code; this proves each part of it for an account that is not the selected
     * one. ZenlyConsent decodes every stored account with the bundled min_valid_version, and every gate reads only the
     * account's own snapshot (nothing about which account is selected). Build N stored acked v1 grants of two linked
     * accounts; build N+1 bundles min_valid_version 2. The selected account grants v2 on its wall and runs again; the
     * other one is never shown and stays stopped from the first launch: no profile sync, no event recorded or flushed, no
     * Tasks, no automatic link and no grant on any bind/start, no consents re-send. An older withdrawal still goes, alone.
     */
    private static void retiredVersionOnEveryAccount() {
        ConsentSnapshot selectedN = acked(grantedBy(TG, TEXT, T0), AUTH);
        ConsentSnapshot otherN = acked(grantedBy(TG2, TEXT, T0 + 1), AUTH);
        running(selectedN, "build N, the selected account");
        running(otherN, "build N, the other account");
        ConsentSnapshot selected = decodeStored(selectedN.storedValues(), 0, 2);
        ConsentSnapshot other = decodeStored(otherN.storedValues(), 0, 2);
        check(selected.tgUserId == TG && other.tgUserId == TG2 && other.record.state == State.GRANTED && other.notice() == Notice.RECONSENT
                && other.storedValues().equals(otherN.storedValues()), "build N+1 loads every stored v1 grant as a derived needs_reconsent, writing nothing");
        ConsentText.Shown v2 = new ConsentText.Shown(2, ConsentManifest.SCOPES, "uz", "v2", SHA2);
        selected = acked(ConsentMachine.grant(selected, v2, Mode.REQUIRED, id(6000), T0 + 100, "1.1.0", AUTH).snapshot, AUTH);
        running(selected, "build N+1, the selected account after its wall's v2 grant");
        stopped(other, "build N+1, the other account's v1 grant");
        check(other.wall(Mode.REQUIRED) == Wall.BLOCKING && other.wall(Mode.OPTIONAL) == Wall.PROMPT, "once selected, its wall asks for the new text");

        // A withdrawal it made before the update still goes, alone and first; its stale grant never does.
        ConsentSnapshot regranted = withdraw(acked(grantedBy(TG2, TEXT, T0), AUTH), T0 + 10);
        String withdrawal = regranted.latestWithdrawEvent().consentId;
        regranted = ConsentMachine.grant(regranted, TEXT, Mode.REQUIRED, id(6001), T0 + 20, "1.0.0", AUTH).snapshot;
        ConsentSnapshot loaded = decodeStored(regranted.storedValues(), 0, 2);
        CustomerPipeline.Facts f = CustomerPipeline.Facts.of(loaded, AUTH);
        check(loaded.effectiveState() == State.NEEDS_RECONSENT && f.pendingWithdraw && loaded.grantJsonIfGranted(AUTH) == null,
                "SETUP: a stale grant made after a withdrawal that is still pending");
        f.linked = false;
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.NONE, "unlinked: no link is made, neither for the withdrawal nor for the stale grant");
        f.linked = true;
        List<ConsentEvent> batch = CustomerPipeline.consentsBatch(loaded, AUTH, true);
        check(CustomerPipeline.next(f) == CustomerPipeline.Step.UPLOAD_CONSENTS && batch.size() == 1 && batch.get(0).consentId.equals(withdrawal),
                "linked: only the older withdrawal is uploaded, even when a grant re-send was asked");
        ConsentSnapshot delivered = ConsentMachine.consentsAnswered(loaded, AUTH, results(withdrawal, "recorded"),
                new ConsentMachine.Effective("withdrawn", withdrawal, 1, T0 + 10), T0 + 200).snapshot;
        stopped(delivered, "the other account once its withdrawal was delivered");
    }

    /**
     * The changed-text half (new: ConsentMachine.operatorChanged, the launch pass over every stored account). Build N
     * (operator values X) stored a Russian grant of the other account; build N+1 (values Y) renders that text with another
     * hash. Before the launch pass the grant still counts as effective — the gap: profile, events, Tasks and links went on
     * under a text this build no longer shows — and the pass stores needs_reconsent for it, while the selected account's
     * grant of this build's text is left alone. Every part then stops, a restart keeps it, and only a new grant of this
     * build's text lifts it.
     */
    private static void changedTextOnEveryAccount(Path assets) throws IOException {
        ConsentManifest manifest = manifest(assets);
        ConsentMachine.Templates templates = templates(assets);
        Map<String, String> buildN = operator("Test Operator MChJ"), buildN1 = operator("Renamed Test Operator MChJ");
        ConsentText.Shown ruN = shown(manifest, templates, "ru", buildN), ruN1 = shown(manifest, templates, "ru", buildN1);
        ConsentText.Shown uzN1 = shown(manifest, templates, "uz", buildN1);
        check(!ruN.sha256.equals(ruN1.sha256), "SETUP: the texts name the operator, so other values give another hash");
        ConsentSnapshot other = acked(grantedBy(TG2, ruN, T0), AUTH);
        ConsentSnapshot selected = acked(grantedBy(TG, uzN1, T0 + 5), AUTH);
        String renderedOther = ConsentMachine.renderedSha256(other.latestGrant(), manifest, buildN1, templates);
        String renderedSelected = ConsentMachine.renderedSha256(selected.latestGrant(), manifest, buildN1, templates);
        check(ruN1.sha256.equals(renderedOther) && uzN1.sha256.equals(renderedSelected),
                "this build's hash of each grant's own version and locale, as its wall would show that text");
        check(ConsentMachine.textChanged(other, renderedOther) && !ConsentMachine.textChanged(selected, renderedSelected), "only build N's text changed");
        running(other, "before the launch pass, the other account's changed grant (the gap)");

        long launch = T0 + 1000;
        Step step = ConsentMachine.operatorChanged(other, renderedOther, launch);
        ConsentSnapshot normalised = step.snapshot;
        check(step.changed && !step.purgeEvents && normalised.record.state == State.NEEDS_RECONSENT && normalised.notice() == Notice.RECONSENT
                && normalised.record.changedAt == launch && normalised.latestGrant().consentId.equals(other.latestGrant().consentId)
                && normalised.outbox.equals(other.outbox) && normalised.acked.equals(other.acked),
                "the launch pass: needs_reconsent (RECONSENT); the grant, outbox, acks and event queue stay");
        check(normalised.recordJson().contains("\"state\":\"needs_reconsent\"") && normalised.recordJson().contains("\"notice\":\"reconsent\""),
                "stored, so it holds without this build's texts");
        Step left = ConsentMachine.operatorChanged(selected, renderedSelected, launch);
        check(!left.changed && left.snapshot == selected, "a grant of this build's text is left alone");
        running(selected, "the selected account");
        stopped(normalised, "the other account's changed grant");
        stopped(decodeStored(normalised.storedValues(), 0, 1), "the other account after a restart");
        check(normalised.wall(Mode.REQUIRED) == Wall.BLOCKING && normalised.wall(Mode.OPTIONAL) == Wall.PROMPT && normalised.withdrawable(),
                "once selected, its wall asks for this build's text; Withdraw stays offered (the acked grant may still be effective)");
        check(!ConsentMachine.operatorChanged(normalised, renderedOther, launch + 1).changed, "a second launch changes nothing");
        ConsentSnapshot again = ConsentMachine.grant(normalised, ruN1, Mode.REQUIRED, id(6010), launch + 10, "1.1.0", AUTH).snapshot;
        check(again.effectiveState() == State.GRANTED && again.recordingAllowed()
                && !ConsentMachine.textChanged(again, ConsentMachine.renderedSha256(again.latestGrant(), manifest, buildN1, templates)),
                "only a new grant of this build's text lifts it, and that grant has not changed");

        // Nothing is compared without this build's hash, and only a stored grant is judged.
        check(!ConsentMachine.operatorChanged(other, null, launch).changed, "no hash of this build (not bundled, refused value): unchanged");
        check(!ConsentMachine.operatorChanged(other, ConsentMachine.renderedSha256(other.latestGrant(), manifest, buildN, templates), launch).changed,
                "the same operator values: unchanged");
        Map<String, ConsentSnapshot> notGranted = new LinkedHashMap<>();
        notGranted.put("none", ConsentSnapshot.empty(TG2, 1));
        notGranted.put("withdrawn", withdraw(other, T0 + 10));
        notGranted.put("withdrawn_remote", ConsentMachine.consentRequired(other, "withdrawn", T0 + 10, T0 + 11).snapshot);
        notGranted.put("needs_reconsent", ConsentMachine.consentRequired(other, "reconsent_required", 0, T0 + 10).snapshot);
        notGranted.put("declined", ConsentMachine.decline(notGranted.get("needs_reconsent"), Mode.OPTIONAL, T0 + 12).snapshot);
        for (Map.Entry<String, ConsentSnapshot> entry : notGranted.entrySet()) {
            check(!ConsentMachine.operatorChanged(entry.getValue(), renderedOther, launch).changed, entry.getKey() + " is left as it is");
        }

        // A rejected grant is asked for again; after a binding loss its notice stays and a manual link no longer lifts it.
        ConsentSnapshot rejected = reject(other);
        ConsentSnapshot asked = ConsentMachine.operatorChanged(rejected, renderedOther, launch).snapshot;
        check(rejected.wall(Mode.REQUIRED) == Wall.NONE && asked.record.state == State.NEEDS_RECONSENT && asked.wall(Mode.REQUIRED) == Wall.BLOCKING
                && asked.notice() == Notice.RECONSENT, "a rejected grant of a changed text: the wall asks again");
        ConsentSnapshot lost = ConsentMachine.operatorChanged(ConsentMachine.bindingLoss(other, T0 + 50).snapshot, renderedOther, launch).snapshot;
        check(lost.record.state == State.NEEDS_RECONSENT && lost.notice() == Notice.LINK_ENDED
                && ConsentMachine.bindingLoss(lost, 0).snapshot.effectiveState() == State.NEEDS_RECONSENT,
                "after a binding loss: the link-ended notice stays, and a manual link no longer lifts it");
        check(ConsentMachine.operatorChanged(other.withMinValidVersion(2), renderedOther, launch).snapshot.notice() == Notice.RECONSENT,
                "a retired version as well: RECONSENT");
    }

    /**
     * ConsentMachine.renderedSha256: this build's hash of a grant's own version and locale, exactly as the wall shows that
     * text; null (nothing is compared) for a version or locale this build does not bundle, an unreadable template, a
     * refused operator value, or no grant, manifest or templates. An older bundled version renders its own template.
     */
    private static void renderedHashes(Path assets) throws IOException {
        ConsentManifest manifest = manifest(assets);
        ConsentMachine.Templates templates = templates(assets);
        Map<String, String> values = operator("Test Operator MChJ");
        for (String locale : ConsentManifest.LOCALES) {
            ConsentText.Shown shown = shown(manifest, templates, locale, values);
            ConsentMachine.Grant grant = new ConsentMachine.Grant(id(6100), 1, shown.sha256, locale, "required", T0);
            check(shown.sha256.equals(ConsentMachine.renderedSha256(grant, manifest, values, templates)), locale + ": the wall's hash of that text");
        }
        ConsentMachine.Grant uz = new ConsentMachine.Grant(id(6101), 1, SHA, "uz", "required", T0);
        check(ConsentMachine.renderedSha256(new ConsentMachine.Grant(id(6102), 3, SHA, "uz", "required", T0), manifest, values, templates) == null,
                "a version this build does not bundle");
        check(ConsentMachine.renderedSha256(uz, manifest, values, file -> null) == null, "an unreadable template");
        check(ConsentMachine.renderedSha256(uz, manifest, operator("Test\nOperator"), templates) == null, "a refused operator value");
        check(ConsentMachine.renderedSha256(null, manifest, values, templates) == null && ConsentMachine.renderedSha256(uz, null, values, templates) == null
                && ConsentMachine.renderedSha256(uz, manifest, values, null) == null, "no grant, manifest or templates");
        // Current version 2, and v1 still bundled in uz only.
        final Map<String, String> files = new LinkedHashMap<>();
        files.put("v1/uz.md", "# v1 {{OPERATOR_NAME}}");
        for (String locale : ConsentManifest.LOCALES) files.put("v2/" + locale + ".md", "# v2 " + locale + " {{OPERATOR_NAME}}");
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("1", versionTree("v1/", Collections.singletonList("uz")));
        versions.put("2", versionTree("v2/", ConsentManifest.LOCALES));
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("schema", ConsentManifest.SCHEMA);
        tree.put("current_version", 2);
        tree.put("min_valid_version", 1);
        tree.put("placeholders", manifest.placeholders);
        tree.put("versions", versions);
        ConsentManifest two = ConsentManifest.fromTree(tree);
        check(two != null && two.currentVersion == 2 && two.version(1) != null, "SETUP: a manifest of two versions");
        ConsentMachine.Templates memory = files::get;
        check(ConsentText.sha256Hex(ConsentText.canonical("# v1 {{OPERATOR_NAME}}", values, two.placeholders))
                .equals(ConsentMachine.renderedSha256(uz, two, values, memory)), "a v1 grant renders v1's template, not the current one");
        check(ConsentMachine.renderedSha256(new ConsentMachine.Grant(id(6103), 1, SHA, "ru", "required", T0), two, values, memory) == null,
                "v1 bundles no ru text");
        check(ConsentMachine.renderedSha256(new ConsentMachine.Grant(id(6104), 3, SHA, "uz", "required", T0), two, values, memory) == null,
                "nor is there a version 3");
    }

    /**
     * Every gate stops {@code s}: it counts as needs_reconsent; over every combination of the facts ZenlyCustomer adds
     * (linked, the grant acked, entries to upload, a grant re-send asked, the profile settled or due, a flush due,
     * confirmed) the pipeline takes no step — no automatic link, consents upload, profile sync, events flush or
     * confirmation — and Tasks never run; nothing is recorded (ZenlyStats); the auto-link gate holds no grant
     * (ZenlyCustomer.hasEffectiveGrant) and no bind/start carries it, automatic or manual (linkGrantJson).
     */
    private static void stopped(ConsentSnapshot s, String label) {
        check(!s.pendingWithdraw(AUTH), "SETUP: " + label + " has no withdrawal to deliver");
        check(s.effectiveState() == State.NEEDS_RECONSENT, label + ": counts as needs_reconsent");
        check(steps(s).equals(EnumSet.of(CustomerPipeline.Step.NONE)), label + ": no link, consents upload, profile sync, events flush or confirmation");
        check(!tasksEver(s), label + ": no Tasks");
        check(!CustomerEvents.recordable(true, s, false), label + ": no event is recorded");
        check(!s.recordingAllowed() && s.grantJsonIfGranted(AUTH) == null && s.grantJsonIfGranted(OTHER) == null,
                label + ": no grant for the auto-link gate or any bind/start");
    }

    /** The control of {@link #stopped}: every step and Tasks are reachable, events are recorded, the grant links. */
    private static void running(ConsentSnapshot s, String label) {
        check(s.effectiveState() == State.GRANTED && s.recordingAllowed() && CustomerEvents.recordable(true, s, false)
                && s.grantJsonIfGranted(AUTH) != null, label + ": granted, recorded, linkable with its grant");
        check(steps(s).containsAll(EnumSet.of(CustomerPipeline.Step.AUTO_LINK, CustomerPipeline.Step.UPLOAD_CONSENTS, CustomerPipeline.Step.PROFILE_SYNC,
                CustomerPipeline.Step.EVENTS_FLUSH, CustomerPipeline.Step.CONFIRM, CustomerPipeline.Step.READY)), label + ": every step is reachable");
        check(tasksEver(s), label + ": Tasks may run");
    }

    /** Every step the pipeline takes for {@code s} at AUTH over all 256 combinations of {@link #facts}. */
    private static Set<CustomerPipeline.Step> steps(ConsentSnapshot s) {
        Set<CustomerPipeline.Step> steps = EnumSet.noneOf(CustomerPipeline.Step.class);
        for (int mask = 0; mask < 1 << 8; mask++) steps.add(CustomerPipeline.next(facts(s, mask)));
        return steps;
    }

    private static boolean tasksEver(ConsentSnapshot s) {
        for (int mask = 0; mask < 1 << 8; mask++) {
            if (CustomerPipeline.tasksMayRun(facts(s, mask))) return true;
        }
        return false;
    }

    /** The consent facts of {@code s} at AUTH; linked, acked, uploads, re-send, profile, flush and confirmation from {@code mask}. */
    private static CustomerPipeline.Facts facts(ConsentSnapshot s, int mask) {
        CustomerPipeline.Facts f = CustomerPipeline.Facts.of(s, AUTH);
        f.linked = (mask & 1) != 0;
        f.grantAcked = (mask & 2) != 0;
        f.uploadable = (mask & 4) != 0;
        f.resendGrant = (mask & 8) != 0;
        f.profileSettled = (mask & 16) != 0;
        f.profileDue = (mask & 32) != 0;
        f.flushDue = (mask & 64) != 0;
        f.confirmed = (mask & 128) != 0;
        return f;
    }

    private static ConsentManifest manifest(Path assets) throws IOException {
        ConsentManifest manifest = ConsentManifest.fromTree(MiniJson.parseFile(assets.resolve("manifest.json")));
        check(manifest != null && manifest.version(1) != null, "SETUP: the vendored manifest parses");
        return manifest;
    }

    /** The vendored templates, strictly decoded as ZenlyConsent reads its assets. */
    private static ConsentMachine.Templates templates(final Path assets) {
        return file -> {
            try {
                return ConsentText.decodeUtf8(Files.readAllBytes(assets.resolve(file)));
            } catch (IOException missing) {
                return null;
            }
        };
    }

    /** The v1 text in {@code locale} as a build with {@code values} shows and hashes it on its wall. */
    private static ConsentText.Shown shown(ConsentManifest manifest, ConsentMachine.Templates templates, String locale, Map<String, String> values) {
        ConsentManifest.Version version = manifest.version(1);
        return ConsentText.shown(version, locale, templates.read(version.text(locale).file), values, manifest.placeholders);
    }

    /** Fake operator values (the real operator is not known yet); two builds differ only in the name. */
    private static Map<String, String> operator(String name) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("OPERATOR_NAME", name);
        values.put("OPERATOR_STIR", "123456789");
        values.put("RESPONSIBLE_PERSON", "Test Person");
        values.put("CONTACT_EMAIL", "privacy@example.org");
        return values;
    }

    /** One manifest version naming {@code directory}{@code <locale>}.md for each locale (the template hash is not checked here). */
    private static Map<String, Object> versionTree(String directory, List<String> locales) {
        Map<String, Object> texts = new LinkedHashMap<>();
        for (String locale : locales) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("file", directory + locale + ".md");
            text.put("template_sha256", SHA);
            texts.put(locale, text);
        }
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("scopes", ConsentManifest.SCOPES);
        version.put("locales", texts);
        return version;
    }

    // ---- grantJsonIfGranted (design 50 §8.1) ----

    private static void grantJsonIfGrantedNulls() {
        ConsentSnapshot granted = acked(granted(T0), AUTH);
        check(granted.grantJsonIfGranted(AUTH).equals(granted.latestGrantJson()) && granted.grantJsonIfGranted(OTHER) != null,
                "granted: the exact latest grant JSON");
        Map<String, ConsentSnapshot> nulls = new LinkedHashMap<>();
        nulls.put("none", empty());
        nulls.put("withdrawn", withdraw(granted, T0 + 5));
        nulls.put("withdrawn_remote", ConsentMachine.consentRequired(granted, "withdrawn", T0 + 5, T0 + 6).snapshot);
        nulls.put("declined", ConsentMachine.decline(ConsentMachine.consentRequired(granted, "reconsent_required", 0, T0 + 1).snapshot, Mode.OPTIONAL, T0 + 2).snapshot);
        nulls.put("needs_reconsent", ConsentMachine.consentRequired(granted, "reconsent_required", 0, T0 + 1).snapshot);
        nulls.put("rejected", reject(granted(T0)));
        nulls.put("binding loss after the grant", ConsentMachine.bindingLoss(granted, T0 + 1).snapshot);
        nulls.put("stale", stale(granted, T0 + 1));
        nulls.put("retired version", granted.withMinValidVersion(2));
        ConsentSnapshot pending = withdraw(granted, T0 + 5);
        pending = ConsentMachine.grant(pending, TEXT, Mode.REQUIRED, id(500), T0 + 10, "1", AUTH).snapshot;
        nulls.put("a pending withdrawal", pending);
        for (Map.Entry<String, ConsentSnapshot> entry : nulls.entrySet()) {
            check(entry.getValue().grantJsonIfGranted(AUTH) == null, "grantJsonIfGranted is null for " + entry.getKey());
        }
        check(pending.effectiveState() == State.GRANTED && pending.recordingAllowed() && pending.pendingWithdraw(AUTH)
                && pending.nextUpload(AUTH).size() == 1 && pending.nextUpload(AUTH).get(0).isWithdraw(),
                "re-granted with an older withdrawal pending: recording allowed, the withdrawal goes first");
        String withdrawId = pending.outbox.get(1).consentId;
        ConsentSnapshot delivered = ConsentMachine.consentsAnswered(pending, AUTH, results(withdrawId, "recorded"),
                new ConsentMachine.Effective("withdrawn", withdrawId, 1, T0 + 5), T0 + 20).snapshot;
        check(delivered.grantJsonIfGranted(AUTH) != null && delivered.grantJsonIfGranted(AUTH).contains(id(500))
                && delivered.nextUpload(AUTH).get(0).consentId.equals(id(500)), "once the withdrawal is acked, the new grant links and uploads");
    }

    // ---- instants ----

    private static void monotonicInstants() {
        ConsentSnapshot s = granted(1000);
        ConsentSnapshot withdrawn = ConsentMachine.withdraw(s, Mode.REQUIRED, "uz", id(60), 900, "1", AUTH).snapshot;
        check(withdrawn.latestWithdrawEvent().recordedAt == 1001, "a withdrawal is after the grant it withdraws, clock behind");
        ConsentSnapshot regranted = ConsentMachine.grant(withdrawn, TEXT, Mode.REQUIRED, id(61), 800, "1", AUTH).snapshot;
        check(regranted.latestGrant().decidedAt == 1002 && regranted.latestGrantEvent().recordedAt == 1002, "a new grant is after the withdrawal");
        ConsentSnapshot remote = ConsentMachine.consentRequired(regranted, "withdrawn", 9000, 1003).snapshot;
        check(ConsentMachine.grant(remote, TEXT, Mode.REQUIRED, id(62), 8000, "1", AUTH).snapshot.latestGrant().decidedAt == 9001,
                "a grant after a remote withdrawal is newer than it");
        check(ConsentMachine.nextInstant(granted(T0), T0 + 500) == T0 + 500, "a later trusted time is used as it is");
        check(ConsentMachine.nextInstant(ConsentMachine.bindingLoss(granted(T0), CustomerJson.MAX_INSTANT).snapshot, 0) == CustomerJson.MAX_INSTANT,
                "instants are clamped to the last instant");
    }

    // ---- immutability ----

    private static void immutability() {
        ConsentSnapshot s = reject(acked(withdraw(acked(granted(T0), AUTH), T0 + 1), AUTH));
        String before = s.storedValues().toString();
        expectImmutable(() -> s.outbox.add(s.outbox.get(0)), "outbox");
        expectImmutable(() -> s.acked.put("x", Collections.<String>emptySet()), "acked map");
        expectImmutable(() -> s.acked.get(AUTH).add("x"), "acked set");
        expectImmutable(() -> s.rejected.put("x", "y"), "rejected map");
        expectImmutable(() -> s.storedValues().put("x", "y"), "stored values");
        expectImmutable(() -> s.pending(AUTH).add(s.outbox.get(0)), "pending list");
        expectImmutable(() -> s.nextUpload(OTHER).clear(), "upload batch");
        ConsentSnapshot next = ConsentMachine.grant(s, TEXT, Mode.REQUIRED, id(70), T0 + 9, "1", AUTH).snapshot;
        check(next != s && s.storedValues().toString().equals(before) && s.record.state == State.WITHDRAWN, "a transition never changes its input");
    }

    // ---- outbox bound ----

    private static void outboxBound() {
        // Linked: every entry acked right away; the outbox stays bounded and keeps the latest grant.
        ConsentSnapshot s = empty();
        long now = T0;
        for (int i = 0; i < 15; i++) {
            s = ConsentMachine.grant(s, TEXT, Mode.REQUIRED, id(1000 + 2 * i), now++, "1", AUTH).snapshot;
            s = acked(s, AUTH);
            s = ConsentMachine.withdraw(s, Mode.REQUIRED, "uz", id(1001 + 2 * i), now++, "1", AUTH).snapshot;
            s = acked(s, AUTH);
            check(s.outbox.size() <= ConsentMachine.MAX_OUTBOX, "acked entries keep the outbox at 20 or fewer");
        }
        check(s.event(s.latestGrant().consentId) != null && s.latestGrantEvent() != null, "the latest grant is never dropped");
        int ackedIds = 0;
        for (Set<String> set : s.acked.values()) {
            for (String id : set) {
                check(s.event(id) != null, "an acked mark only for a kept entry");
                ackedIds++;
            }
        }
        check(ackedIds == s.outbox.size(), "every kept entry is acked once");
        // Unlinked: nothing acked; older grants go first, un-acked withdrawals never.
        ConsentSnapshot unlinked = empty();
        for (int i = 0; i < 25; i++) {
            unlinked = ConsentMachine.grant(unlinked, TEXT, Mode.REQUIRED, id(2000 + 2 * i), now++, "1", null).snapshot;
            unlinked = ConsentMachine.withdraw(unlinked, Mode.REQUIRED, "uz", id(2001 + 2 * i), now++, "1", null).snapshot;
        }
        int withdrawals = 0, grants = 0;
        for (ConsentEvent event : unlinked.outbox) {
            if (event.isWithdraw()) withdrawals++; else grants++;
        }
        check(withdrawals == 25 && grants == 1 && unlinked.latestGrantEvent() != null, "every un-acked withdrawal is kept; only the latest grant stays");
        check(unlinked.outbox.get(unlinked.outbox.size() - 1).consentId.equals(id(2049)) && unlinked.outbox.get(0).consentId.equals(id(2001)),
                "oldest first");
        // Rejected entries go before older un-acked grants; the latest acked entry is kept.
        ConsentSnapshot mixed = acked(granted(T0), AUTH);
        String firstAcked = mixed.latestGrant().consentId;
        for (int i = 0; i < 12; i++) {
            mixed = ConsentMachine.withdraw(mixed, Mode.REQUIRED, "uz", id(3000 + 2 * i), now++, "1", AUTH).snapshot;
            mixed = ConsentMachine.grant(mixed, TEXT, Mode.REQUIRED, id(3001 + 2 * i), now++, "1", AUTH).snapshot;
            mixed = reject(mixed);
        }
        check(mixed.event(firstAcked) != null && mixed.grantMayBeEffective(), "the latest acked entry (a grant) is kept");
        check(mixed.outbox.size() <= ConsentMachine.MAX_OUTBOX + 12, "bounded except for un-acked withdrawals");
        for (String id : mixed.rejected.keySet()) check(mixed.event(id) != null, "a rejected mark only for a kept entry");
    }

    // ---- upload batches ----

    private static void uploadBatches() {
        List<Object> outbox = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            outbox.add(MiniJson.parse(ConsentEvent.withdraw(id(4000 + i), TG, ConsentManifest.SCOPES, 1, "uz", "required", T0 + i, "1").json()));
        }
        ConsentSnapshot s = ConsentSnapshot.decode(TG, null, outbox, null, null, 0, 1);
        List<ConsentEvent> first = s.nextUpload(AUTH);
        check(first.size() == 10 && first.get(0).consentId.equals(id(4000)) && first.get(9).consentId.equals(id(4009)), "at most 10 withdrawals per request");
        ConsentEvent.requestBody(first);
        List<ConsentMachine.Result> results = new ArrayList<>();
        for (ConsentEvent event : first) results.add(new ConsentMachine.Result(event.consentId, "recorded", false));
        s = ConsentMachine.consentsAnswered(s, AUTH, results, null, T0 + 100).snapshot;
        check(s.nextUpload(AUTH).size() == 2 && s.nextUpload(AUTH).get(0).consentId.equals(id(4010)), "then the remaining two");
        ConsentSnapshot mixed = withdraw(acked(granted(T0), AUTH), T0 + 5);
        mixed = ConsentMachine.grant(mixed, TEXT, Mode.REQUIRED, id(4100), T0 + 6, "1", AUTH).snapshot;
        List<ConsentEvent> batch = mixed.nextUpload(AUTH);
        check(batch.size() == 1 && batch.get(0).isWithdraw(), "a withdrawal goes alone before a later grant");
        mixed = ConsentMachine.consentsAnswered(mixed, AUTH, results(batch.get(0).consentId, "recorded"), null, T0 + 7).snapshot;
        check(mixed.nextUpload(AUTH).size() == 1 && mixed.nextUpload(AUTH).get(0).consentId.equals(id(4100)), "then the grant, alone");
        check(mixed.nextUpload(OTHER).size() == 1 && mixed.nextUpload(OTHER).get(0).isGrant(), "another authority starts from the first entry");
        ConsentSnapshot reset = ConsentMachine.clearAcked(mixed, AUTH).snapshot;
        check(reset.pending(AUTH).size() == 3 && !reset.acked.containsKey(AUTH) && !ConsentMachine.clearAcked(reset, AUTH).changed
                && !ConsentMachine.clearAcked(reset, null).changed, "a new binding un-acks every entry there");
    }

    // ---- store codec ----

    private static void codec() {
        ConsentSnapshot granted = granted(T0);
        String grantId = granted.latestGrant().consentId;
        check(Arrays.asList("consent.v1.555000111", "consent.outbox.v1.555000111").equals(new ArrayList<>(granted.storedValues().keySet())),
                "a fresh grant stores the record and the outbox");
        check(("[" + granted.latestGrantJson() + "]").equals(granted.storedValues().get("consent.outbox.v1.555000111")), "the stored outbox array");
        List<ConsentSnapshot> samples = Arrays.asList(granted, acked(acked(granted, AUTH), OTHER), reject(granted), ConsentMachine.decline(empty(), Mode.OPTIONAL, T0).snapshot,
                ConsentMachine.consentsAnswered(withdraw(acked(granted, AUTH), T0 + 1), AUTH, results(id(0), "recorded"), null, T0).snapshot,
                stale(granted, T0 + 3), ConsentMachine.consentRequired(granted, "reconsent_required", 0, T0 + 4).snapshot,
                ConsentMachine.consentRequired(granted, "withdrawn", T0 + 9, T0 + 9).snapshot);
        for (ConsentSnapshot sample : samples) {
            ConsentSnapshot decoded = decodeStored(sample.storedValues(), sample.bindingLostAt);
            check(decoded.storedValues().equals(sample.storedValues()) && decoded.effectiveState() == sample.effectiveState()
                    && decoded.notice() == sample.notice() && decoded.record.activeElsewhere == sample.record.activeElsewhere,
                    "stored values decode to the same state: " + sample.record.state);
        }
        ConsentSnapshot withdrawn = withdraw(acked(granted, AUTH), T0 + 1);
        ConsentSnapshot elsewhere = ConsentMachine.consentsAnswered(withdrawn, AUTH, results(withdrawn.latestWithdrawEvent().consentId, "superseded"),
                new ConsentMachine.Effective("granted", id(9), 1, T0 + 2), T0 + 3).snapshot;
        check(decodeStored(elsewhere.storedValues(), 0).record.activeElsewhere, "active elsewhere survives a restart");
        // Keys.
        check("consent.acked.v1.555000111@10.0.2.2".equals(ConsentSnapshot.Keys.acked(TG, OTHER))
                && "consent.rejected.v1.555000111".equals(ConsentSnapshot.Keys.rejected(TG)), "key names");
        ConsentSnapshot.Keys.Key key = ConsentSnapshot.Keys.parse("consent.acked.v1.555000111@127.0.0.1:8443");
        check(key != null && key.kind == ConsentSnapshot.Keys.Kind.ACKED && key.tgUserId == TG && "127.0.0.1:8443".equals(key.authority), "an acked key");
        key = ConsentSnapshot.Keys.parse("consent.v1.9007199254740991");
        check(key != null && key.kind == ConsentSnapshot.Keys.Kind.RECORD && key.tgUserId == ConsentEvent.MAX_TELEGRAM_USER_ID, "the largest id");
        for (String other : new String[] {"consent.v1.0", "consent.v1.012", "consent.v1.abc", "consent.v1.", "consent.acked.v1.5@", "consent.acked.v1.@x",
                "consent.acked.v1.5", "consent.v1.9007199254740992", "consent.outbox.v2.5", "autolink.v1.5@x", "consent.v1.-5", null}) {
            check(ConsentSnapshot.Keys.parse(other) == null, "not a consent key: " + other);
        }
        // Corrupt stored values read as absent, never as someone else's state.
        Map<String, Object> record = MiniJson.object(MiniJson.parse(granted.recordJson()));
        check(ConsentMachine.Record.fromTree(record, TG + 1) == null, "a record of another user is refused");
        Map<String, Object> partial = MiniJson.object(MiniJson.parse(granted.recordJson()));
        partial.remove("text_sha256");
        check(ConsentMachine.Record.fromTree(partial, TG) == null, "partial grant fields are refused");
        Map<String, Object> noGrant = MiniJson.object(MiniJson.parse("{\"v\":1,\"tg_user_id\":555000111,\"state\":\"granted\",\"changed_at\":\"2026-10-29T10:00:00Z\"}"));
        check(ConsentMachine.Record.fromTree(noGrant, TG) == null, "a grant state needs the grant fields");
        Map<String, Object> format = MiniJson.object(MiniJson.parse(granted.recordJson()));
        format.put("v", 2);
        check(ConsentMachine.Record.fromTree(format, TG) == null, "another record format is refused");
        Map<String, Object> noneState = MiniJson.object(MiniJson.parse(granted.recordJson()));
        noneState.put("state", "none");
        check(ConsentMachine.Record.fromTree(noneState, TG) == null, "none is never stored");
        Map<String, Object> odd = MiniJson.object(MiniJson.parse(granted.recordJson()));
        odd.put("notice", "link_ended");
        odd.put("active_elsewhere", true);
        odd.put("future_key", 1);
        ConsentMachine.Record tolerant = ConsentMachine.Record.fromTree(odd, TG);
        check(tolerant != null && tolerant.cause == Notice.NONE && !tolerant.activeElsewhere, "marks of other states are ignored, unknown keys too");
        List<Object> outbox = new ArrayList<>();
        outbox.add(MiniJson.parse(granted.latestGrantJson()));
        outbox.add(MiniJson.parse(granted.latestGrantJson()));
        outbox.add(MiniJson.parse(ConsentEvent.withdraw(id(77), TG + 1, ConsentManifest.SCOPES, 1, "uz", "required", T0, "1").json()));
        outbox.add("not an event");
        Map<String, Object> ackedTrees = new LinkedHashMap<>();
        ackedTrees.put(AUTH, Arrays.asList(grantId, id(99)));
        ackedTrees.put(OTHER, "broken");
        Map<String, Object> rejectedTree = new LinkedHashMap<>();
        rejectedTree.put(grantId, "Bad Code");
        rejectedTree.put(id(98), "invalid_request");
        ConsentSnapshot decoded = ConsentSnapshot.decode(TG, "garbage", outbox, ackedTrees, rejectedTree, 0, 1);
        check(decoded.record.state == State.NONE && decoded.outbox.size() == 1 && decoded.acked.get(AUTH).size() == 1 && !decoded.acked.containsKey(OTHER)
                && decoded.rejected.size() == 1 && "rejected".equals(decoded.rejected.get(grantId)),
                "invalid record, duplicate / foreign / broken entries and marks of unknown ids are left out");
    }

    private static ConsentSnapshot decodeStored(Map<String, String> values, long lostAt) { return decodeStored(values, lostAt, 1); }

    /** What a launch loads from {@code values}: ZenlyConsent decodes every stored account with the bundled {@code minValid}. */
    private static ConsentSnapshot decodeStored(Map<String, String> values, long lostAt, int minValid) {
        Object record = null, outbox = null, rejected = null;
        Map<String, Object> acked = new LinkedHashMap<>();
        long tg = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            ConsentSnapshot.Keys.Key key = ConsentSnapshot.Keys.parse(entry.getKey());
            check(key != null, "a stored key parses: " + entry.getKey());
            tg = key.tgUserId;
            Object tree = MiniJson.parse(entry.getValue());
            if (key.kind == ConsentSnapshot.Keys.Kind.RECORD) record = tree;
            else if (key.kind == ConsentSnapshot.Keys.Kind.OUTBOX) outbox = tree;
            else if (key.kind == ConsentSnapshot.Keys.Kind.ACKED) acked.put(key.authority, tree);
            else rejected = tree;
        }
        return ConsentSnapshot.decode(tg == 0 ? TG : tg, record, outbox, acked, rejected, lostAt, minValid);
    }

    // ---- consents answers ----

    private static void answers(Path fixtures) throws Exception {
        Map<String, Object> file = MiniJson.object(MiniJson.parseFile(fixtures.resolve("consents-valid.json")));
        for (Object item : MiniJson.array(file.get("cases"))) {
            Map<String, Object> testCase = MiniJson.object(item);
            Map<String, Object> expected = MiniJson.object(testCase.get("expected"));
            List<?> consents = MiniJson.array(MiniJson.object(testCase.get("body")).get("consents"));
            List<?> outcomes = MiniJson.array(expected.get("outcomes"));
            List<Object> resultTrees = new ArrayList<>();
            for (int i = 0; i < outcomes.size(); i++) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("consent_id", MiniJson.object(consents.get(i)).get("consent_id"));
                result.put("outcome", outcomes.get(i));
                resultTrees.add(result);
            }
            Map<String, Object> answerTree = new LinkedHashMap<>();
            answerTree.put("schema_version", 1);
            answerTree.put("results", resultTrees);
            answerTree.put("consent", expected.get("consent"));
            answerTree.put("erasure", expected.get("erasure"));
            ConsentMachine.Answer answer = ConsentMachine.Answer.fromTree(answerTree);
            Map<String, Object> consent = MiniJson.object(expected.get("consent"));
            check(answer.results.size() == outcomes.size() && answer.effective != null && answer.effective.state.equals(consent.get("state"))
                    && answer.effective.effectiveAt == CustomerJson.parseInstant((String) consent.get("effective_at")),
                    testCase.get("id") + ": the answer's results and effective block parse");
        }
        Map<String, Object> odd = MiniJson.object(MiniJson.parse("{\"results\":[{\"consent_id\":\"" + id(1) + "\",\"outcome\":\"duplicate\",\"foreign\":true},"
                + "{\"consent_id\":\"x\",\"outcome\":\"recorded\"},{\"consent_id\":\"" + id(2) + "\",\"outcome\":\"lost\"},7],"
                + "\"consent\":{\"state\":\"none\",\"consent_id\":null,\"text_version\":null,\"effective_at\":null}}"));
        ConsentMachine.Answer answer = ConsentMachine.Answer.fromTree(odd);
        check(answer.results.size() == 1 && answer.results.get(0).foreign && "duplicate".equals(answer.results.get(0).outcome)
                && "none".equals(answer.effective.state) && answer.effective.consentId == null && answer.effective.textVersion == 0
                && answer.effective.effectiveAt == 0, "foreign duplicates; unreadable items are left out; null fields read as absent");
        check(ConsentMachine.Effective.fromTree(MiniJson.parse("{\"state\":\"paused\"}")) == null && ConsentMachine.Answer.fromTree(null).effective == null
                && ConsentMachine.Answer.fromTree(null).results.isEmpty(), "an unknown effective state or body reads as absent");
    }

    // ---- helpers ----

    private static ConsentSnapshot empty() { return ConsentSnapshot.empty(TG, 1); }

    private static ConsentSnapshot granted(long at) {
        return ConsentMachine.grant(empty(), TEXT, Mode.REQUIRED, id(++ids + 100000), at, "1.0.0", AUTH).snapshot;
    }

    /** {@code tg}'s grant of {@code text} at {@code at} (bundled min_valid_version 1). */
    private static ConsentSnapshot grantedBy(long tg, ConsentText.Shown text, long at) {
        return ConsentMachine.grant(ConsentSnapshot.empty(tg, 1), text, Mode.REQUIRED, id(++ids + 100000), at, "1.0.0", AUTH).snapshot;
    }

    private static ConsentSnapshot withdraw(ConsentSnapshot s, long at) {
        Step step = ConsentMachine.withdraw(s, Mode.REQUIRED, "uz", id(++ids + 200000), at, "1.0.0", AUTH);
        check(step.changed, "withdrawal helper");
        return step.snapshot;
    }

    private static ConsentSnapshot stale(ConsentSnapshot s, long at) {
        Step step = ConsentMachine.consentStale(s, s.latestGrantJson(), at);
        check(step.changed, "stale helper");
        return step.snapshot;
    }

    /**
     * What ZenlyConsent.setBindingLoss leaves when every commit succeeds: its feed (ConsentMachine.feedBindingLoss: the
     * loss, then the grant it ended stored, ConsentMachine.linkEnded) through a store that publishes every changed step.
     */
    private static ConsentSnapshot fed(ConsentSnapshot s, long lostAt) {
        final ConsentSnapshot[] published = {s};
        ConsentMachine.feedBindingLoss(transition -> {
            Step step = transition.apply(published[0]);
            if (step.changed) published[0] = step.snapshot;
            return step;
        }, lostAt);
        return published[0];
    }

    /** The grant bind/start would carry at AUTH, or null. */
    private static String grantJson(ConsentSnapshot s) { return s.grantJsonIfGranted(AUTH); }

    /** Every outbox entry acked at {@code authority}. */
    private static ConsentSnapshot acked(ConsentSnapshot s, String authority) {
        List<ConsentMachine.Result> results = new ArrayList<>();
        for (ConsentEvent event : s.outbox) results.add(new ConsentMachine.Result(event.consentId, "recorded", false));
        return ConsentMachine.consentsAnswered(s, authority, results, null, 0).snapshot;
    }

    private static ConsentSnapshot reject(ConsentSnapshot s) {
        Step step = ConsentMachine.rejected(s, Collections.singletonList(s.latestGrant().consentId), "consent_text_mismatch");
        check(step.changed, "rejection helper");
        return step.snapshot;
    }

    private static List<ConsentMachine.Result> results(String consentId, String outcome) {
        return Collections.singletonList(new ConsentMachine.Result(consentId, outcome, false));
    }

    private static String id(int n) { return String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", n); }

    private interface Action { void run(); }

    private static void expectImmutable(Action action, String label) {
        try {
            action.run();
            check(false, label + " is immutable");
        } catch (UnsupportedOperationException expected) {
            check(true, label + " is immutable");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
